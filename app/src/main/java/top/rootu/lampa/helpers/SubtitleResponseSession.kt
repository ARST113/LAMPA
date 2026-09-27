package top.rootu.lampa.helpers

/** A single HTTP response owns its framing. Only immutable metadata is shared. */
class SubtitleResponseSession(val sourceId: Long, val responseId: Long,
    seed: MkvSubtitleStream.Metadata?, emit: (String)->Unit) {
    private val parser=MkvSubtitleStream(emit)
    private var closed=false
    init { seed?.let {parser.seedMetadata(it)} }
    fun seedMetadata(metadata: MkvSubtitleStream.Metadata) { if(!closed)parser.seedMetadata(metadata) }
    fun feed(bytes: ByteArray,count: Int) { if(!closed)parser.feed(bytes,0,count) }
    fun metadata(): MkvSubtitleStream.Metadata? = if(closed)null else parser.metadata()
    fun close() {closed=true;parser.beginResponse()}
}
