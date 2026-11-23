package studio.otherstuff.ambulando.core.surfaces

import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.math.BigDecimal

/** Portable v2 JSON boundary; deliberately independent of the legacy tracker schema. */
object GenericJson {
    const val MAX_BYTES = 1048576
    fun scalar(s: String): Boolean {
        var i=0
        while(i<s.length) {
            val c=s[i++]
            if(c.isHighSurrogate()) { if(i==s.length || !s[i++].isLowSurrogate()) return false }
            else if(c.isLowSurrogate()) return false
        }
        return true
    }
    fun text(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun decode(bytes: ByteArray): JsonObject { require(bytes.size<=MAX_BYTES); return decode(text(bytes)) }
    fun decode(source: String): JsonObject = document(source,MAX_BYTES,32,50000,true)
    internal fun envelope(source: String): JsonObject = document(source,MAX_BYTES+2048,33,50020,false)
    private fun document(source: String,maxBytes: Int,maxDepth: Int,maxNodes: Int,validateRoot: Boolean): JsonObject {
        require(source.toByteArray().size<=maxBytes && !source.startsWith('\uFEFF'))
        // Scan keys before the JSON decoder can discard duplicates. The decoder checks full token grammar.
        var i=0; var nodes=0
        fun ws() { while(i<source.length && source[i] in " \t\r\n") i++ }
        fun str(): String {
            require(i<source.length && source[i]=='"'); val start=i++
            while(i<source.length) {
                val c=source[i++]
                if(c=='\\') { require(i<source.length); i++ }
                else if(c=='"') return Json.parseToJsonElement(source.substring(start,i)).jsonPrimitive.content
            }
            error("Unterminated string")
        }
        fun scan(depth: Int) {
            require(depth<=maxDepth && ++nodes<=maxNodes); ws(); require(i<source.length)
            when(source[i]) {
                '{','[' -> {
                    val obj=source[i++]=='{'; val end=if(obj) '}' else ']'; val keys=mutableSetOf<String>(); ws()
                    if(i<source.length && source[i]==end) { i++; return }
                    while(true) {
                        if(obj) { require(keys.add(str())); ws(); require(i<source.length && source[i++]==':') }
                        scan(depth+1); ws(); require(i<source.length)
                        if(source[i]==end) { i++; return }
                        require(source[i++]==','); ws()
                    }
                }
                '"' -> str()
                else -> { val start=i; while(i<source.length && source[i] !in ",]} \t\r\n") i++; require(i>start) }
            }
        }
        scan(1); ws(); require(i==source.length)
        val root=Json.parseToJsonElement(source).jsonObject
        return if(validateRoot) validate(root) else root
    }
    fun validate(root: JsonObject): JsonObject {
        var nodes=0
        fun visit(v: JsonElement,depth: Int) {
            require(++nodes<=50000 && depth<=32)
            when(v) {
                is JsonObject -> v.forEach { (k,x) -> require(k !in setOf("__proto__","prototype","constructor") && scalar(k) && k.toByteArray().size<=65536); visit(x,depth+1) }
                is JsonArray -> v.forEach { visit(it,depth+1) }
                is JsonPrimitive -> if(v.isString) require(scalar(v.content) && v.content.toByteArray().size<=65536)
                    else if(v!=JsonNull && v.booleanOrNull==null) {
                        val d=v.content.toDouble(); require(d.isFinite() && kotlin.math.abs(d)<=9007199254740991.0 && !(d==0.0 && d.toRawBits()<0))
                    }
            }
        }
        visit(root,1); require(canonical(root).toByteArray().size<=MAX_BYTES); return root
    }
    private val scalarOrder=Comparator<String> { a,b ->
        val x=a.codePoints().toArray(); val y=b.codePoints().toArray()
        var result=0; var i=0
        while(i<minOf(x.size,y.size) && result==0) { result=x[i].compareTo(y[i]); i++ }
        if(result==0) x.size.compareTo(y.size) else result
    }
    private fun number(v: JsonPrimitive): String {
        val d=v.content.toDouble()
        require(d.isFinite() && kotlin.math.abs(d)<=9007199254740991.0 && !(d==0.0 && d.toRawBits()<0))
        if(d==0.0) return "0"
        // Select the shortest decimal that round-trips to this IEEE double, as ECMAScript does.
        val exact=BigDecimal(d); var shortest=BigDecimal.valueOf(d)
        for(p in 1..17) {
            val candidates=listOf(java.math.RoundingMode.HALF_EVEN,java.math.RoundingMode.FLOOR,java.math.RoundingMode.CEILING)
                .map { exact.round(java.math.MathContext(p,it)) }.filter { it.toDouble()==d }
            if(candidates.isNotEmpty()) {
                shortest=candidates.minWith(compareBy<BigDecimal> { it.subtract(exact).abs() }
                    .thenBy { it.stripTrailingZeros().unscaledValue().abs().testBit(0) })
                break
            }
        }
        val n=shortest.stripTrailingZeros()
        if(kotlin.math.abs(d)>=0.000001) return n.toPlainString()
        val exponent=n.precision()-n.scale()-1
        val digits=n.unscaledValue().abs().toString()
        val mantissa=digits.take(1)+(if(digits.length>1) "."+digits.drop(1) else "")
        return (if(d<0) "-" else "")+mantissa+"e"+(if(exponent>=0) "+" else "")+exponent
    }
    fun canonical(v: JsonElement): String = when(v) {
        is JsonObject -> v.keys.sortedWith(scalarOrder).joinToString(",","{","}") { JsonPrimitive(it).toString()+":"+canonical(v.getValue(it)) }
        is JsonArray -> v.joinToString(",","[","]") { canonical(it) }
        is JsonPrimitive -> if(v.isString || v==JsonNull || v.booleanOrNull!=null) v.toString() else number(v)
    }
}
