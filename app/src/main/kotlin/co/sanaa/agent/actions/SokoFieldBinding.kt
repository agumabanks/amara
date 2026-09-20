package co.sanaa.agent.actions

/** Exact field association; unknown or ambiguous fields never fall back to the first editor. */
object SokoFieldBinding {
    data class Node(val value:String, val labels:List<String>, val editable:Boolean)
    fun canonical(field:String):String? = when(field.lowercase().filter(Char::isLetterOrDigit)) {
        "name","title","productname" -> "Product Name"
        "price","sellingprice" -> "Selling Price"
        "stock","stockqty","stockquantity" -> "Stock Qty"
        "description","productdescription" -> "Product Description"
        else -> null
    }
    fun select(nodes:List<Node>,field:String):Int? {
        val wanted=canonical(field) ?: return null
        fun labels(node:Node)=node.labels.flatMap { it.lines() }.mapNotNull(::canonical)
        val direct=nodes.indices.filter { nodes[it].editable && wanted in labels(nodes[it]) }
        if(direct.isNotEmpty()) return direct.singleOrNull()
        val candidates=mutableSetOf<Int>()
        for(i in nodes.indices) {
            if(nodes[i].editable || wanted !in labels(nodes[i])) continue
            for(j in i+1..minOf(i+3,nodes.lastIndex)) {
                if(labels(nodes[j]).any { it!=wanted }) break
                if(nodes[j].editable) { candidates+=j;break }
            }
        }
        return candidates.singleOrNull()
    }
    fun reopenTitle(old:String,field:String,value:String):String = if(canonical(field)=="Product Name") value else old
    fun matches(field:String,expected:String,observed:String):Boolean = when(canonical(field)) {
        "Selling Price","Stock Qty" -> {
            fun number(s:String)=s.trim().replace(",", "").toBigDecimalOrNull()
            val a=number(expected);val b=number(observed)
            a!=null && b!=null && a.compareTo(b)==0
        }
        null -> false
        else -> expected.replace("\r\n","\n").trim()==observed.replace("\r\n","\n").trim()
    }
}
