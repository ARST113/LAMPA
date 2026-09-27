package top.rootu.lampa.helpers
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SubtitleUiMailboxTest {
    @Test fun burstsScheduleOnceAndReplaceOldGenerations() {
        val scheduled=ArrayList<()->Unit>();val delivered=ArrayList<String>()
        val mailbox=SubtitleUiMailbox({scheduled.add(it)},{batch,ack -> delivered.addAll(batch);ack()})
        repeat(10000){mailbox.offer(JSONObject().put("type","cues").put("session",1).put("url","first").put("value",it).toString())}
        assertEquals(1,scheduled.size)
        mailbox.clear()
        mailbox.offer("""{"type":"selected","session":2,"url":"second"}""")
        mailbox.offer("""{"type":"cues","session":2,"url":"second","value":"current"}""")
        assertEquals(1,scheduled.size)
        scheduled.removeAt(0).invoke()
        assertEquals(2,delivered.size)
        assertTrue(delivered.all{JSONObject(it).getInt("session")==2})
        assertTrue(delivered.last().contains("current"))
    }
    @Test fun rendererAcknowledgementBoundsInFlightAndNavigationRetiresIt() {
        val batches=ArrayList<List<String>>();val acks=ArrayList<()->Unit>()
        val mailbox=SubtitleUiMailbox({it()},{batch,ack -> batches.add(batch);acks.add(ack)})
        repeat(10000){mailbox.offer("""{"type":"cues","session":1,"url":"first","value":$it}""")}
        assertEquals(1,batches.size)
        acks[0]()
        assertEquals(2,batches.size)
        assertTrue(batches.last().single().contains("9999"))
        mailbox.reset()
        mailbox.offer("""{"type":"tracks","session":2,"url":"second"}""")
        assertEquals(3,batches.size)
        acks[1]() // an old page cannot release the current page's in-flight batch
        mailbox.offer("""{"type":"cues","session":2,"url":"second"}""")
        assertEquals(3,batches.size)
        acks[2]();assertEquals(4,batches.size)
        mailbox.close();acks[3]();mailbox.offer("""{"type":"cues","session":2}""")
        assertEquals(4,batches.size)
    }
}
