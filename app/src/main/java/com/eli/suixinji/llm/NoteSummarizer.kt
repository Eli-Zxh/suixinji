package com.eli.suixinji.llm

import com.eli.suixinji.asr.TranscriptSegment
import com.eli.suixinji.note.NoteComposer
import com.eli.suixinji.util.AppLog

/**
 * 三段式笔记归纳 agent（用户指定架构；M2.6 按「减少 decode 输出」精简；
 * M2.10 增加双语支持——提示词/解析标记/组装文案随 [LangPack] 切换）：
 *
 *  阶段1 概览：只产出「题目 + 摘要」供拼装使用——不再让模型输出段落划分
 *          （那是给自己看的草稿，白白多 decode 一大段，真机上占总耗时相当比例）。
 *          全文不超预算时一次调用；超预算时逐切片出摘要再合并出全局题目与摘要。
 *  阶段2 分段：逐切片独立调用，只返回「### 小节 + 要点」总结结果本身，
 *          提示词明确禁止复述/搬运该段原文（输出 token 越少 decode 越快）。
 *  阶段3 审核与自测：合并为一次调用——通过则同一回复直接带出「## 自测与思考」，
 *          省一次整稿调用；打回则按「重做:2,5」格式给出需要重做的部分编号，
 *          只重做对应切片（其余复用首轮结果），至多一轮。
 *
 * 关键设计：
 *  - 每次调用使用全新 Conversation（提示词隔离，杜绝上下文累积溢出后模型回显
 *    prompt、把转写原文带进结果——v1 实测踩到的坑）。
 *  - 提示词不含任何固定条目数字，数量由内容决定。
 *  - 最终笔记不含转写原文；原文只存在于 audio 目录的 .srt 字幕里。
 *  - 回显检测：输出中出现原文的取样片段即判定该次调用失败并重试。
 *  - assemble 为每个分段写 <!-- part=i -->（审核定位用）与
 *    <!-- t=起-止 -->（照片插图时间定位用）两个 HTML 注释，渲染不可见。
 *  - language="en" 时输出英文笔记（Title/Abstract/Review verdict/Redo 等
 *    解析标记同步切换；中英混说课程仍建议用 zh——识别语种由 ASR 设置决定）。
 */
object NoteSummarizer {

  enum class Stage { OVERVIEW, SECTIONS, REVIEW, QUESTIONS }

  /** 单次调用输入的字符预算（中文 ≈ 1.2 token/字，8K 上下文下留足输出空间）。 */
  private const val SLICE_CHARS = 4500

  /** 全文一次概览的上限；超出则切片多轮概览。 */
  private const val OVERVIEW_SINGLE_CHARS = 5000

  /** 回显检测取样窗口长度。 */
  private const val ECHO_WINDOW = 60

  /** 笔记语言包：提示词、解析标记、组装文案全部随语言切换（M2.10）。 */
  private data class LangPack(
    val lang: String,
    val system: String,
    val titleKey: String,          // 题目行解析前缀（含冒号由 extractAfter 兜底）
    val abstractKey: String,
    val passMark: String,          // 审核通过判定串
    val failMark: String,          // 审核打回判定串
    val verdictMark: String,       // 审核结论行前缀（过滤审核意见用）
    val redoMark: String,          // 重做编号行前缀
    val redoRegex: Regex,
    val selfTestHeading: String,
    val titleFallback: String,
    val sourceFallback: String,
    val sourceLabel: String,
    val aiNoteLabel: String,
    val abstractHeading: String,
    val emptyNotes: String,
    val overviewSingle: (text: String, source: String) -> String,
    val overviewSlice: (text: String, source: String, i: Int, n: Int, span: String) -> String,
    val overviewMerge: (briefs: String, source: String) -> String,
    val section: (text: String, source: String, i: Int, n: Int, span: String) -> String,
    val review: (draft: String, n: Int) -> String,
    val questions: (body: String) -> String,
    val redoSuffix: (feedback: String) -> String,
    val brief: (i: Int, span: String) -> String,
  )

  private val zh = LangPack(
    lang = "zh",
    system = "你是严谨的课堂笔记整理助手。只输出被要求的 Markdown 内容本身；" +
      "用书面语归纳与重组，绝不逐字搬运讲稿原文；不编造讲稿中不存在的内容。",
    titleKey = "题目",
    abstractKey = "摘要",
    passMark = "审核结论：通过",
    failMark = "审核结论：打回",
    verdictMark = "审核结论",
    redoMark = "重做",
    redoRegex = Regex("重做：\\s*([0-9,，、\\s]+)"),
    selfTestHeading = "## 自测与思考",
    titleFallback = "课堂笔记",
    sourceFallback = "录音",
    sourceLabel = "来源",
    aiNoteLabel = "AI 归纳",
    abstractHeading = "## 摘要",
    emptyNotes = "（空笔记）",
    overviewSingle = { text, source ->
      "以下是一次讲座/课程的完整转写稿（原始文件：$source）。请只输出两行 Markdown：\n" +
        "第一行以「题目：」开头，为本次内容拟标题（能判断学科时用「学科 主题」格式）；\n" +
        "第二行以「摘要：」开头，给一段全文摘要。\n" +
        "不要输出段落划分、不要复述原文、不要输出其他任何内容。\n\n$text"
    },
    overviewSlice = { text, source, i, n, span ->
      "以下是讲座转写稿的「部分$i/$n」（时间段 $span，原始文件：$source）。" +
        "请只输出一行以「摘要：」开头的本部分摘要；" +
        "不要输出小节划分、不要复述原文、不要输出其他任何内容。\n\n$text"
    },
    overviewMerge = { briefs, source ->
      "以下是同一讲座各部分的概览摘要（原始文件：$source）。请只输出两行 Markdown：\n" +
        "第一行以「题目：」开头，给出全局标题（能判断学科时用「学科 主题」格式）；\n" +
        "第二行以「摘要：」开头，给一段整合后的全文摘要。\n" +
        "不要输出其他任何内容。\n\n$briefs"
    },
    section = { text, source, i, n, span ->
      "以下是讲座转写稿「部分$i/$n」（时间段 $span，原始文件：$source）。" +
        "请把该部分整理为 Markdown 总结：每个小节以「### 小节标题」开头，节内为「- 」开头的要点列表；" +
        "小节划分与要点数量由内容决定；用简练书面语归纳重组，保留专业术语原词。" +
        "只输出总结结果本身——禁止输出或复述任何转写原文，不要解释，不要任何前后缀。\n\n$text"
    },
    review = { draft, n ->
      "以下是一份由讲座转写稿生成的课堂笔记草稿，共 $n 个部分" +
        "（各部分正文前有 <!-- part=编号 --> 注释标记）。请先审核质量：" +
        "是否遗漏了讲稿中的主要知识内容；是否存在编造、搬运转写原文、或空泛无知识性的部分。然后二选一输出：\n" +
        "A. 整体合格——第一行输出「审核结论：通过」，随后直接输出「## 自测与思考」小节：" +
        "以「- [ ] 」开头的 Markdown 任务列表，覆盖核心概念、易混点与实际应用，数量与深度由内容决定。\n" +
        "B. 不合格——第一行输出「审核结论：打回」，第二行输出「重做:编号列表」" +
        "（如「重做:2,5」，编号对应 part 注释），随后用一句话说明需要重做的原因。\n" +
        "只输出以上内容，不要解释格式。\n\n$draft"
    },
    questions = { body ->
      "以下是整理完成的笔记。请为其生成「## 自测与思考」小节：若干检验理解的问题，" +
        "以「- [ ] 」开头的 Markdown 任务列表，覆盖核心概念、易混点与实际应用，" +
        "数量与深度由内容决定。只输出该小节的 Markdown。\n\n$body"
    },
    redoSuffix = { fb ->
      "\n\n上一版总结被审核打回，审核意见：$fb\n请按意见重做本部分总结，仍然只输出总结结果本身。"
    },
    brief = { i, span -> "【部分$i（$span）】" },
  )

  private val en = LangPack(
    lang = "en",
    system = "You are a rigorous lecture-notes assistant. Output only the requested Markdown " +
      "content itself; paraphrase in formal written English; never copy the transcript " +
      "verbatim; never fabricate content absent from the transcript.",
    titleKey = "Title:",
    abstractKey = "Abstract:",
    passMark = "Review verdict: PASS",
    failMark = "Review verdict: REJECT",
    verdictMark = "Review verdict",
    redoMark = "Redo",
    redoRegex = Regex("Redo:\\s*([0-9,，、\\s]+)"),
    selfTestHeading = "## Self-test & Reflection",
    titleFallback = "Lecture Notes",
    sourceFallback = "recording",
    sourceLabel = "Source",
    aiNoteLabel = "AI notes",
    abstractHeading = "## Abstract",
    emptyNotes = "(empty note)",
    overviewSingle = { text, source ->
      "Below is the full transcript of a lecture (source file: $source). Output exactly two lines of Markdown:\n" +
        "Line 1 starts with \"Title:\", a title for this content (use \"Subject: Topic\" format when the discipline is clear);\n" +
        "Line 2 starts with \"Abstract:\", a one-paragraph summary of the whole text.\n" +
        "No section outline, no quoting of the transcript, nothing else.\n\n$text"
    },
    overviewSlice = { text, source, i, n, span ->
      "Below is part $i/$n of a lecture transcript (time range $span, source file: $source). " +
        "Output exactly one line starting with \"Abstract:\", a summary of this part. " +
        "No section outline, no quoting, nothing else.\n\n$text"
    },
    overviewMerge = { briefs, source ->
      "Below are overview summaries of the parts of one lecture (source file: $source). Output exactly two lines of Markdown:\n" +
        "Line 1 starts with \"Title:\", the global title (\"Subject: Topic\" format when clear);\n" +
        "Line 2 starts with \"Abstract:\", an integrated one-paragraph summary.\n" +
        "Nothing else.\n\n$briefs"
    },
    section = { text, source, i, n, span ->
      "Below is part $i/$n of a lecture transcript (time range $span, source file: $source). " +
        "Summarize it as Markdown: each subsection starts with \"### Subsection title\", followed by \"- \" bullet points; " +
        "the number of subsections and bullets is dictated by the content; paraphrase concisely in formal written English, keeping technical terms. " +
        "Output only the summary itself — never reproduce transcript text, no explanations, no prefixes or suffixes.\n\n$text"
    },
    review = { draft, n ->
      "Below is a lecture-notes draft generated from a transcript, with $n parts " +
        "(each preceded by a <!-- part=n --> marker). Review its quality: are major knowledge points missing; " +
        "is anything fabricated, copied from the transcript, or vacuous. Then output exactly one of:\n" +
        "A. Overall acceptable — first line \"Review verdict: PASS\", then immediately output the \"## Self-test & Reflection\" section: " +
        "a Markdown task list starting with \"- [ ]\", covering core concepts, confusable points and applications, depth as content dictates.\n" +
        "B. Not acceptable — first line \"Review verdict: REJECT\", second line \"Redo: 2,5\" " +
        "(numbers matching the part markers), then one sentence explaining what must be redone.\n" +
        "Output nothing else.\n\n$draft"
    },
    questions = { body ->
      "Below is a finished set of notes. Generate its \"## Self-test & Reflection\" section: " +
        "comprehension-check questions as a Markdown task list starting with \"- [ ]\", " +
        "covering core concepts, confusable points and applications. Output only that section's Markdown.\n\n$body"
    },
    redoSuffix = { fb ->
      "\n\nThe previous summary of this part was rejected in review. Reviewer comments: $fb\n" +
        "Redo this part accordingly, still outputting only the summary itself."
    },
    brief = { i, span -> "[Part $i ($span)]" },
  )

  private fun pack(lang: String): LangPack = if (lang == "en") en else zh

  /**
   * 执行归纳，返回最终 Markdown（不含转写原文）。须在 IO 线程调用。
   *
   * [onCheckpoint]：阶段性产出回调（概览完成 / 每个分段完成后），调用方把半成品落盘——
   * 长任务中途崩溃（原生崩溃无法被 UncaughtExceptionHandler 捕获）时已完成部分不丢。
   */
  suspend fun run(
    llm: SummarizerLlm,
    segments: List<TranscriptSegment>,
    sourceName: String,
    language: String = "zh",
    onProgress: (Stage, Int, Int) -> Unit = { _, _, _ -> },
    onCheckpoint: suspend (String) -> Unit = {},
  ): String {
    llm.initialize()
    val P = pack(language)
    val fullText = segments.joinToString("\n") { it.text }
    require(fullText.isNotBlank()) { "转写内容为空" }
    val tStart = System.currentTimeMillis()

    // ---- 切片（确定性：TranscriptSegment 边界） ----
    val slices = chunkSegments(segments, SLICE_CHARS)
    AppLog.i("归纳：全文 ${fullText.length} 字 → ${slices.size} 个切片（笔记语言=${P.lang}）")

    // ---- 阶段1：概览 → 只取题目 / 摘要 ----
    var title = ""
    var abstractText = ""
    if (slices.size == 1) {
      onProgress(Stage.OVERVIEW, 1, 1)
      val reply = callLlm(llm, P.system, P.overviewSingle(fullText, sourceName), "概览") {
        !hasEcho(it, fullText)
      }
      title = extractAfter(reply, P.titleKey).ifBlank { sourceName.substringBeforeLast('.') }
      abstractText = extractAfter(reply, P.abstractKey)
    } else {
      val briefs = ArrayList<String>(slices.size)
      slices.forEachIndexed { i, slice ->
        onProgress(Stage.OVERVIEW, i + 1, slices.size)
        val text = slice.joinToString("\n") { it.text }
        val span = spanOf(slice)
        val reply = callLlm(
          llm, P.system,
          P.overviewSlice(text, sourceName, i + 1, slices.size, span),
          "概览 ${i + 1}/${slices.size}",
        ) { !hasEcho(it, text) }
        briefs.add(P.brief(i + 1, span) + "\n$reply")
      }
      onProgress(Stage.OVERVIEW, slices.size, slices.size)
      val merged = callLlm(llm, P.system, P.overviewMerge(briefs.joinToString("\n\n"), sourceName), "概览合并") { true }
      title = extractAfter(merged, P.titleKey).ifBlank { sourceName.substringBeforeLast('.') }
      abstractText = extractAfter(merged, P.abstractKey)
    }
    AppLog.i("归纳：概览完成 题目=${title.length}字 摘要=${abstractText.length}字 阶段耗时=${System.currentTimeMillis() - tStart}ms")

    // ---- 阶段2 分段 + 阶段3 审核与自测（打回仅重做对应部分，至多一轮） ----
    // 每个分段对应的录音时间范围（毫秒），组装时写进时间锚，供照片插图定位
    val sliceSpans = slices.map { s -> (s.firstOrNull()?.startMs ?: 0L) to (s.lastOrNull()?.endMs ?: 0L) }
    val sectionBodies = arrayOfNulls<String>(slices.size)
    // 概览完成即可落第一版半成品（题目+摘要），崩溃也不至于全空
    runCatching {
      onCheckpoint(assemble(P, title, abstractText, emptyList(), sliceSpans, sourceName, llm.label, segments))
    }
    var redoParts: Set<Int> = emptySet()
    var reviewFeedback: String? = null
    var retried = false
    var sectionMs = 0L
    while (true) {
      slices.forEachIndexed { i, slice ->
        if (retried && (i + 1) !in redoParts) return@forEachIndexed // 未被打回的部分复用首轮结果
        onProgress(Stage.SECTIONS, i + 1, slices.size)
        val text = slice.joinToString("\n") { it.text }
        val span = spanOf(slice)
        val prompt = buildString {
          append(P.section(text, sourceName, i + 1, slices.size, span))
          if (retried && (i + 1) in redoParts && !reviewFeedback.isNullOrBlank()) {
            append(P.redoSuffix(reviewFeedback!!))
          }
        }
        val tSec = System.currentTimeMillis()
        sectionBodies[i] = callLlm(
          llm, P.system, prompt,
          "分段 ${i + 1}/${slices.size}" + if (retried) "（重做）" else "",
        ) { !hasEcho(it, text) }
        sectionMs += System.currentTimeMillis() - tSec
        // 每个分段完成即落盘一次（半成品持续可恢复）
        runCatching {
          onCheckpoint(
            assemble(
              P, title, abstractText,
              sectionBodies.take(i + 1).map { it.orEmpty() },
              sliceSpans, sourceName, llm.label, segments,
            ),
          )
        }
      }
      val bodies = sectionBodies.map { it.orEmpty() }

      val draft = assemble(P, title, abstractText, bodies, sliceSpans, sourceName, llm.label, segments)
      onProgress(Stage.REVIEW, 1, 1)
      val tReview = System.currentTimeMillis()
      val verdict = callLlm(
        llm, P.system, P.review(draft, slices.size),
        "审核与自测" + if (retried) "（重做后）" else "",
      ) { isVerdict(it, P) }
      val passed = isPass(verdict, P)
      AppLog.i(
        "归纳：审核结论=${if (passed) "通过" else "打回"} replyChars=${verdict.length} " +
          "概览+分段耗时=${tReview - tStart}ms（其中分段 ${sectionMs}ms） 审核耗时=${System.currentTimeMillis() - tReview}ms",
      )

      if (passed) {
        // 自测题随本回复直接带出，省一次整稿调用
        val questions = dedupeQuestions(verdict.substringAfter(P.passMark, "").trim())
        if (questions.isNotBlank()) {
          return assemble(P, title, abstractText, bodies, sliceSpans, sourceName, llm.label, segments, questionsSection = questions)
        }
        AppLog.w("归纳：审核通过但未带出自测部分，单独补一次生成")
        onProgress(Stage.QUESTIONS, 1, 1)
        val q = dedupeQuestions(callLlm(llm, P.system, P.questions(draft), "自测题") { !hasEcho(it, draft) })
        return assemble(P, title, abstractText, bodies, sliceSpans, sourceName, llm.label, segments, questionsSection = q)
      }

      if (retried) {
        AppLog.w("归纳：复审仍未通过，采纳当前草稿并单独生成自测题")
        onProgress(Stage.QUESTIONS, 1, 1)
        val q = dedupeQuestions(callLlm(llm, P.system, P.questions(draft), "自测题") { !hasEcho(it, draft) })
        return assemble(P, title, abstractText, bodies, sliceSpans, sourceName, llm.label, segments, questionsSection = q)
      }

      redoParts = parseRedoParts(verdict, P)
      reviewFeedback = verdict.lineSequence()
        .filterNot {
          it.trimStart().startsWith(P.verdictMark) || it.trimStart().startsWith(P.redoMark)
        }
        .joinToString("\n").trim().ifBlank { verdict }
      if (redoParts.isEmpty()) {
        AppLog.w("归纳：审核打回但未给出部分编号，重做全部分段")
      } else {
        AppLog.w("归纳：审核打回，仅重做部分 $redoParts")
      }
      retried = true
    }
  }

  /** 审核行识别：zh「审核结论：…」 / en「Review verdict: …」。 */
  private fun isVerdict(reply: String, P: LangPack): Boolean =
    reply.lineSequence().any { it.trimStart().startsWith(P.verdictMark) }

  private fun isPass(reply: String, P: LangPack): Boolean =
    reply.lineSequence().any { it.trimStart().startsWith(P.passMark) }

  // ---------- LLM 调用（独立会话 + 校验重试） ----------

  private fun callLlm(
    llm: SummarizerLlm,
    systemInstruction: String,
    prompt: String,
    label: String,
    validator: (String) -> Boolean,
  ): String {
    var attempt = 0
    while (true) {
      attempt++
      val t0 = System.currentTimeMillis()
      // 输出预算按输入长度浮动，而不是按阶段写死；避免长内容被固定上限截断。
      val maxOut = (prompt.length / 2 + 512).coerceIn(512, 8192)
      val reply = llm.chat(systemInstruction, prompt, maxOut)
      AppLog.i(
        "归纳：$label 第${attempt}次调用 耗时=${System.currentTimeMillis() - t0}ms replyChars=${reply.length}",
      )
      if (reply.isBlank()) {
        if (attempt >= 2) throw IllegalStateException("$label：LLM 返回空内容")
        AppLog.w("归纳：$label 返回为空，重试")
        continue
      }
      if (!validator(reply)) {
        if (attempt >= 2) throw IllegalStateException("$label：输出校验未通过（疑似回显或异常输出）")
        AppLog.w("归纳：$label 输出校验未通过（疑似回显），重试")
        continue
      }
      return reply
    }
  }

  /**
   * 轻量模型容易把同一组问题重复多遍，且最后一条常被 token 上限截断。
   * 按行去重，只保留完整问题；非列表行（标题等）原样保留。
   */
  private fun dedupeQuestions(text: String): String {
    val seen = HashSet<String>()
    val out = ArrayList<String>()
    for (line in text.lineSequence()) {
      val key = line.trim()
      if (key.isBlank()) {
        out.add(line)
        continue
      }
      val isItem = key.startsWith("- [ ]") || key.startsWith("- [x]")
      if (isItem) {
        if (!key.endsWith("？") && !key.endsWith("?")) continue
        if (!seen.add(key)) continue
      }
      out.add(line)
    }
    return out.joinToString("\n").trim()
  }

  /** 取样检测回显：原文中均匀取若干 60 字窗口，任一出现在输出即判定回显。 */
  fun hasEcho(reply: String, source: String): Boolean {
    if (source.length < ECHO_WINDOW || reply.length < ECHO_WINDOW) return false
    val positions = 7
    val step = maxOf(1, (source.length - ECHO_WINDOW) / positions)
    var checked = 0
    var i = 0
    while (i + ECHO_WINDOW <= source.length && checked < positions) {
      if (reply.contains(source.substring(i, i + ECHO_WINDOW))) return true
      i += step
      checked++
    }
    return false
  }

  // ---------- 切片 ----------

  /** 按字符预算切块，保持 TranscriptSegment 边界。 */
  fun chunkSegments(
    segments: List<TranscriptSegment>,
    maxChars: Int = SLICE_CHARS,
  ): List<List<TranscriptSegment>> {
    val chunks = ArrayList<List<TranscriptSegment>>()
    var buf = ArrayList<TranscriptSegment>()
    var len = 0
    for (seg in segments) {
      buf.add(seg)
      len += seg.text.length
      if (len >= maxChars) {
        chunks.add(buf)
        buf = ArrayList()
        len = 0
      }
    }
    if (buf.isNotEmpty()) chunks.add(buf)
    return chunks
  }

  // ---------- 输出解析 ----------

  private fun spanOf(slice: List<TranscriptSegment>): String =
    "${NoteComposer.formatDur(slice.first().startMs)}–${NoteComposer.formatDur(slice.last().endMs)}"

  /** 提取「Key：」后的内容（首个匹配行；en 的 Key 自带冒号）。 */
  private fun extractAfter(reply: String, key: String): String =
    reply.lineSequence().firstOrNull { it.trimStart().startsWith(key) }
      ?.substringAfter(key)?.trim()?.trimStart('：', ':')
      ?: ""

  /** 解析「重做:2,5」/「Redo:2,5」里的部分编号。 */
  private fun parseRedoParts(verdict: String, P: LangPack): Set<Int> {
    val m = P.redoRegex.find(verdict) ?: return emptySet()
    return m.groupValues[1].split(Regex("[,，、\\s]+"))
      .mapNotNull { it.trim().toIntOrNull() }
      .filter { it > 0 }
      .toSet()
  }

  // ---------- 组装 ----------

  private fun assemble(
    P: LangPack,
    title: String,
    abstractText: String,
    sectionBodies: List<String>,
    sliceSpans: List<Pair<Long, Long>>,
    sourceName: String,
    backend: String,
    segments: List<TranscriptSegment>,
    questionsSection: String? = null,
  ): String {
    val sb = StringBuilder()
    sb.appendLine("# ${title.ifBlank { P.titleFallback }}")
    sb.appendLine()
    sb.appendLine(
      "> ${P.sourceLabel}：${sourceName.ifBlank { P.sourceFallback }} · ${P.aiNoteLabel}（$backend） · " +
        NoteComposer.formatDur(segments.lastOrNull()?.endMs ?: 0L),
    )
    sb.appendLine()
    if (abstractText.isNotBlank()) {
      sb.appendLine(P.abstractHeading)
      sb.appendLine(abstractText.trim())
      sb.appendLine()
    }
    sectionBodies.forEachIndexed { i, body ->
      if (i > 0) sb.appendLine()
      // part 标记：审核打回时定位需重做的部分；时间锚：照片插图按录音时刻定位
      sb.appendLine("<!-- part=${i + 1} -->")
      val span = sliceSpans.getOrNull(i)
      if (span != null) sb.appendLine("<!-- t=${span.first}-${span.second} -->")
      sb.append(body.trim())
      sb.appendLine()
    }
    questionsSection?.let { q ->
      sb.appendLine()
      sb.appendLine(q.trim())
      sb.appendLine()
    }
    return sb.toString()
  }

}
