package engine.tools

import engine.state.DivVariableValue
import engine.state.EngineStateBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

data class DivActionUrl(val host: String, val query: Map<String, String>) {
    val capability: String? get() = query["tool"]
    val resultVar: String? get() = query["result_var"]
    val args: Map<String, String> get() = query

    companion object {
        fun parse(raw: String): DivActionUrl? {
            if (!raw.startsWith("div-action://")) return null
            return try {
                val uri = URI(raw)
                val host = uri.host ?: uri.authority ?: ""
                val queryMap = mutableMapOf<String, String>()
                val queryStr = uri.rawQuery ?: raw.substringAfter('?', "")
                if (queryStr.isNotBlank()) {
                    queryStr.split('&').forEach { param ->
                        val parts = param.split('=', limit = 2)
                        val k = URLDecoder.decode(parts[0], "UTF-8")
                        val v = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""
                        queryMap[k] = v
                    }
                }
                DivActionUrl(host, queryMap)
            } catch (_: Exception) {
                val afterScheme = raw.removePrefix("div-action://")
                val host = afterScheme.substringBefore('?')
                val queryStr = afterScheme.substringAfter('?', "")
                val queryMap = mutableMapOf<String, String>()
                if (queryStr.isNotBlank()) {
                    queryStr.split('&').forEach { param ->
                        val parts = param.split('=', limit = 2)
                        queryMap[parts[0]] = if (parts.size > 1) parts[1] else ""
                    }
                }
                DivActionUrl(host, queryMap)
            }
        }
    }
}

object ResultBinder {
    fun bind(r: ToolResult, q: Map<String, String>, ctx: ToolContext) {
        val resultVarRaw = q["result_var"]
        val resultVar = resultVarRaw?.let { if (it.startsWith("user.")) it else "user.$it" }

        when (r) {
            is ToolResult.Success -> {
                resultVar?.let {
                    val stateVal = (r.data["state"] as? String) ?: "ok"
                    ctx.variables(it, stateVal)
                }
                r.data.forEach { (k, v) ->
                    if (k != "candidates") {
                        resultVar?.let { ctx.variables("${it}_$k", v?.toString() ?: "") }
                    }
                }
                (r.data["candidates"] as? List<*>)?.let { c ->
                    q["candidates_var"]?.let { cVar ->
                        val safeCVar = if (cVar.startsWith("user.")) cVar else "user.$cVar"
                        val jsonArr = buildJsonArray {
                            c.forEach { item -> put(item.toString()) }
                        }.toString()
                        ctx.variables(safeCVar, jsonArr)
                    }
                }
            }
            is ToolResult.Unsupported -> resultVar?.let { ctx.variables(it, "unsupported") }
            is ToolResult.PermissionDenied -> resultVar?.let { ctx.variables(it, "denied") }
            is ToolResult.Failed -> resultVar?.let { ctx.variables(it, "failed") }
        }
    }
}

class DivActionRouter(
    private val registry: ToolRegistry,
    private val provenance: ProvenanceChecker = ProvenanceChecker(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    suspend fun handle(
        raw: String,
        provenanceType: Provenance = Provenance.Publisher,
        ctx: ToolContext = ToolContext()
    ): ToolResult? {
        val a = DivActionUrl.parse(raw) ?: return null
        if (a.host != "terminal.tool.invoke") return null
        val tool = a.query["tool"] ?: return null
        val resultVar = a.query["result_var"]

        if (!provenance.allows(provenanceType, tool)) {
            val deniedResult = ToolResult.PermissionDenied(tool, "Provenance does not allow capability: $tool")
            ResultBinder.bind(deniedResult, a.query, ctx)
            return deniedResult
        }

        val result = registry.dispatch(tool, ToolArgs(a.query), ctx)
        ResultBinder.bind(result, a.query, ctx)
        return result
    }
}
