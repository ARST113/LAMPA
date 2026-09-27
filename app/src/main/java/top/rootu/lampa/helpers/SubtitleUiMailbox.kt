package top.rootu.lampa.helpers

import org.json.JSONObject

/** One renderer batch in flight plus three coalesced message kinds, including when JS stalls. */
class SubtitleUiMailbox(private val schedule: (()->Unit)->Unit,
                        private val deliver: (List<String>,()->Unit)->Unit) {
    private val latest=LinkedHashMap<String,String>()
    private var scheduled=false
    private var inFlight=false
    private var identity=""
    private var epoch=0L
    private var deliveryId=0L
    private var closed=false
    @Synchronized fun offer(message: String) {
        if(closed || message.length>4_100_000)return
        val parsed=JSONObject(message)
        val type=parsed.optString("type")
        if(type !in setOf("tracks","selected","cues"))return
        val next=parsed.optString("url")+"#"+parsed.optLong("session")
        if(next!=identity){latest.clear();identity=next}
        latest[type]=message
        scheduleNext()
    }
    @Synchronized private fun scheduleNext() {
        if(closed || scheduled || inFlight || latest.isEmpty())return
        scheduled=true
        val page=epoch
        schedule {
            val batch:List<String>
            val id:Long
            synchronized(this) {
                if(closed || page!=epoch)return@schedule
                scheduled=false
                if(latest.isEmpty())return@schedule
                batch=latest.values.toList();latest.clear()
                inFlight=true;id=++deliveryId
            }
            deliver(batch) {
                synchronized(this) {
                    if(page==epoch && id==deliveryId && inFlight) {
                        inFlight=false;scheduleNext()
                    }
                }
            }
        }
    }
    /** Selection changes keep the in-flight limit until its acknowledgement. */
    @Synchronized fun clear(){latest.clear();identity=""}
    /** Only a destroyed/navigated document retires a batch without acknowledgement. */
    @Synchronized fun reset(){clear();epoch++;scheduled=false;inFlight=false}
    @Synchronized fun close(){closed=true;reset()}
}
