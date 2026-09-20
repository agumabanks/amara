package co.sanaa.agent.modules

import android.icu.text.BreakIterator
import java.util.Locale

object WhatsAppTone {
    fun polish(reply: String, incoming: String): String {
        val serious=Regex("(?i)\\b(pay|paid|payment|momo|airtel|ugx|fee|price|cost|refund|complaint|late|wrong|sorry|manager|failed|problem)\\b").containsMatchIn("$incoming $reply")
        val friendly=Regex("(?i)\\b(thanks|thank you|congratulations|congrats|great|awesome|love)\\b").containsMatchIn(incoming)
        var allowance=if(!serious && friendly) 1 else 0
        val iterator=BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(reply) }
        val result=StringBuilder();var start=iterator.first();var end=iterator.next()
        while(end!=BreakIterator.DONE) {
            val cluster=reply.substring(start,end)
            val emoji=cluster.codePoints().anyMatch { it in 0x1F000..0x1FAFF || it in 0x2600..0x27BF || it==0x20E3 }
            if(!emoji || allowance-- > 0) result.append(cluster)
            start=end;end=iterator.next()
        }
        return result.toString().replace(Regex("[ \\t]{2,}")," ").trim()
    }
}
