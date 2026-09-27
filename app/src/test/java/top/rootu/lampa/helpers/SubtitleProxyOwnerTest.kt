package top.rootu.lampa.helpers

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SubtitleProxyOwnerTest {
    @Test fun closeSerializesWithPublicationAndClearsExtractorAfterRegistration() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val closed=CountDownLatch(1)
        val proxy=AtomicReference<SubtitleProxy>();val events=ArrayList<String>()
        val owner=SubtitleProxyOwner({
            entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS))
            SubtitleProxy(object:RelayObserver{}).also{proxy.set(it)}
        },{_,_->events.add("registered")},{events.add("cleared")})
        val ticket=owner.request()
        val register=Thread{owner.register(ticket,"http://media.test/file.mkv","http://lampa.test",emptyMap())}.apply{start()}
        assertTrue(entered.await(2,TimeUnit.SECONDS))
        val destroy=Thread{owner.close();closed.countDown()}.apply{start()}
        assertFalse(closed.await(30,TimeUnit.MILLISECONDS))
        release.countDown();register.join(2000);destroy.join(2000)
        assertEquals(0L,closed.count)
        assertEquals(listOf("registered","cleared"),events)
        assertThrows(IllegalStateException::class.java){proxy.get().register("http://media.test/file.mkv","http://lampa.test",emptyMap())}
        assertThrows(IllegalStateException::class.java){owner.register(ticket,"http://media.test/file.mkv","http://lampa.test",emptyMap())}
    }
    @Test fun cancelInvalidatesCookieCallbacksBeforeAnyProxyIsCreated() {
        var created=0
        val owner=SubtitleProxyOwner({created++;SubtitleProxy(object:RelayObserver{})},{_,_->},{})
        val ticket=owner.request();owner.cancel()
        assertThrows(IllegalStateException::class.java){owner.register(ticket,"http://media.test/file.mkv","http://lampa.test",emptyMap())}
        assertEquals(0,created);owner.close()
    }
}
