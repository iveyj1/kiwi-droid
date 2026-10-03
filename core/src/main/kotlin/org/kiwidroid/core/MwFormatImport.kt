package org.kiwidroid.core

import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Reads `format` and `frequency` from English Wikipedia's {{Infobox radio station}} wikitext. */
object WikiInfobox {
    private val LIST_TEMPLATES = setOf("ubl", "unbulleted list", "plainlist", "plain list", "flatlist", "hlist", "bulleted list", "ublist")
    private val KEEP_LAST_ARGUMENT = setOf("nowrap", "nobr", "lang", "small", "nowrap begin")

    /** Top-level parameters of the first radio-station infobox, or empty when there is none. */
    fun params(wikitext: String): Map<String, String> {
        val start = Regex("""\{\{\s*Infobox[ _]radio[ _]station""", RegexOption.IGNORE_CASE).find(wikitext)?.range?.first
            ?: return emptyMap()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var braces = 0
        var brackets = 0
        var i = start
        while (i < wikitext.length) {
            val two = wikitext.substring(i, minOf(i + 2, wikitext.length))
            when {
                two == "{{" -> { braces++; if (braces > 1) current.append(two); i += 2; continue }
                two == "}}" -> { braces--; if (braces == 0) break; current.append(two); i += 2; continue }
                two == "[[" -> { brackets++; current.append(two); i += 2; continue }
                two == "]]" -> { brackets--; current.append(two); i += 2; continue }
                wikitext[i] == '|' && braces == 1 && brackets == 0 -> { parts += current.toString(); current.clear() }
                else -> current.append(wikitext[i])
            }
            i++
        }
        parts += current.toString()
        return parts.drop(1).mapNotNull { part ->
            val eq = part.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
            part.substring(0, eq).trim().lowercase(Locale.ROOT) to part.substring(eq + 1).trim()
        }.toMap()
    }

    /** Plain text from a parameter value: links, list templates, refs, comments and markup reduced. */
    fun clean(value: String): String {
        var text = value.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<ref[^>]*/>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<ref[^>]*>.*?</ref>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("\\[\\[(?:[^|\\]]*\\|)?([^\\]]*)]]"), "$1")
        val template = Regex("""\{\{([^{}]*)}}""")
        while (true) {
            val match = template.find(text) ?: break
            val args = match.groupValues[1].split('|')
            val name = args.first().trim().lowercase(Locale.ROOT)
            val replacement = when {
                name in LIST_TEMPLATES -> args.drop(1).filterNot { '=' in it }.joinToString("\n")
                name in KEEP_LAST_ARGUMENT -> args.last()
                else -> ""
            }
            text = text.replaceRange(match.range, replacement)
        }
        val plain = text.replace(Regex("<[^>]+>"), "").replace("'''", "").replace("''", "")
            .replace("&nbsp;", " ").replace("&ndash;", "–").replace("&mdash;", "—").replace("&amp;", "&")
            .split('\n').map { it.trim().trimStart('*', '#', ' ').trim(' ', ',', ';') }.filter { it.isNotEmpty() }
            .joinToString(" / ").replace(Regex("\\s+"), " ").trim()
        // Malformed infobox source leaks parameter syntax; better no format than a wrong one.
        return if (Regex("""[{}\[\]|=]""").containsMatchIn(plain)) "" else plain.take(80)
    }

    /** True when the infobox frequency mentions [khz] as a whole number (e.g. "1030 [[kHz]]"). */
    fun frequencyMatches(params: Map<String, String>, khz: Int): Boolean =
        Regex("""(?<![\d.])$khz(?![\d.])""").containsMatchIn(params["frequency"].orEmpty())
}

/** Build-time network import: Wikidata links stations to articles; Wikipedia supplies the infobox format. */
private class MwFormatFetcher {
    private val client = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).build()
    private val userAgent = "KiwiDroid-MW-format-import/1.0 (build-time; offline Android KiwiSDR client)"

    private fun get(url: String, form: Map<String, String>? = null): JsonElement {
        val builder = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json")
        form?.let { builder.post(FormBody.Builder().apply { it.forEach { (k, v) -> add(k, v) } }.build()) }
        repeat(4) { attempt ->
            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) return Json.parseToJsonElement(response.body!!.string())
                    if (response.code != 429 && response.code < 500) error("HTTP ${response.code} from $url")
                }
            } catch (e: java.io.IOException) {
                println("Retrying after ${e.javaClass.simpleName}: ${e.message}")
            }
            Thread.sleep(5000L * (attempt + 1))
        }
        error("Repeated failures from $url")
    }

    fun sparql(query: String): List<Map<String, String>> =
        get("https://query.wikidata.org/sparql", mapOf("query" to query, "format" to "json"))
            .jsonObject.getValue("results").jsonObject.getValue("bindings").jsonArray
            .map { b -> b.jsonObject.mapValues { it.value.jsonObject.getValue("value").jsonPrimitive.content } }

    /** Requested title → wikitext, following normalization and redirects. */
    fun wikitext(titles: Collection<String>): Map<String, String> {
        val result = hashMapOf<String, String>()
        titles.distinct().chunked(50).forEachIndexed { i, batch ->
            if (i > 0) Thread.sleep(250)
            val url = "https://en.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query").addQueryParameter("prop", "revisions")
                .addQueryParameter("rvprop", "content").addQueryParameter("rvslots", "main")
                .addQueryParameter("redirects", "1").addQueryParameter("format", "json")
                .addQueryParameter("formatversion", "2").addQueryParameter("maxlag", "5")
                .addQueryParameter("titles", batch.joinToString("|")).build().toString()
            val query = get(url).jsonObject.getValue("query").jsonObject
            val renamed = hashMapOf<String, String>()
            listOf("normalized", "redirects").forEach { key ->
                query[key]?.jsonArray?.forEach { r -> renamed[r.jsonObject.getValue("from").jsonPrimitive.content] = r.jsonObject.getValue("to").jsonPrimitive.content }
            }
            val pages = query["pages"]?.jsonArray.orEmpty().mapNotNull { page ->
                val p = page.jsonObject
                val text = p["revisions"]?.jsonArray?.firstOrNull()?.jsonObject?.get("slots")?.jsonObject
                    ?.get("main")?.jsonObject?.get("content")?.jsonPrimitive?.content ?: return@mapNotNull null
                p.getValue("title").jsonPrimitive.content to text
            }.toMap()
            batch.forEach { title ->
                var t = title
                repeat(3) { renamed[t]?.let { t = it } }
                pages[t]?.let { result[title] = it }
            }
        }
        return result
    }
}

/** `./gradlew :core:importMwFormats --args="--stations mw_stations.tsv --out mw_formats.tsv"`. */
object MwFormatImport {
    @JvmStatic fun main(args: Array<String>) {
        val options = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
        val db = File(requireNotNull(options["stations"]) { "--stations is required" }).reader().use(MwStationDb::read)
        val out = File(requireNotNull(options["out"]) { "--out is required" })
        val fetcher = MwFormatFetcher()

        println("Wikidata: FCC facility IDs and call signs → English Wikipedia articles…")
        val byFacility = fetcher.sparql("""SELECT ?fid ?title WHERE { ?s wdt:P1400 ?fid .
            ?a schema:about ?s ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?title . }""")
            .groupBy({ it.getValue("fid").toIntOrNull() }, { it.getValue("title") })
        val byCall = fetcher.sparql("""SELECT ?call ?title WHERE { ?s wdt:P2317 ?call ; wdt:P17 ?c .
            VALUES ?c { wd:Q16 wd:Q96 wd:Q241 }
            ?a schema:about ?s ; schema:isPartOf <https://en.wikipedia.org/> ; schema:name ?title . }""")
            .groupBy({ MexicoAm.normalizeCall(it.getValue("call")) }, { it.getValue("title") })

        // Wikidata rarely records Canadian/Mexican call signs, so also try Wikipedia's usual article titles;
        // the infobox frequency check below rejects FM/TV namesakes.
        val candidates = db.stations.associateWith { s ->
            if (s.country == "US") s.facilityIds.flatMap { byFacility[it].orEmpty() }.distinct()
            else if (FccAmImport.isPlaceholderCall(s.call)) emptyList()
            else (byCall[MexicoAm.normalizeCall(s.call)].orEmpty() + listOf("${s.call} (AM)", "${s.call}-AM", s.call)).distinct()
        }.filterValues { it.isNotEmpty() }
        println("Wikipedia: reading ${candidates.values.flatten().distinct().size} articles for ${candidates.size} stations…")
        val texts = fetcher.wikitext(candidates.values.flatten())

        var noFormat = 0
        var frequencyMismatch = 0
        val rows = candidates.mapNotNull { (station, titles) ->
            // Non-US matches are by call sign only, so require the infobox frequency to confirm the AM station.
            val matches = titles.mapNotNull { t -> texts[t]?.let { t to WikiInfobox.params(it) } }
                .filter { (_, p) -> station.country == "US" || WikiInfobox.frequencyMatches(p, station.khz) }
            if (matches.isEmpty()) { frequencyMismatch++; return@mapNotNull null }
            val (title, params) = matches.first()
            val format = params["format"]?.let(WikiInfobox::clean).orEmpty()
            if (format.isEmpty()) { noFormat++; return@mapNotNull null }
            station to (format to title.replace('\t', ' '))
        }.sortedWith(compareBy({ it.first.khz }, { it.first.facilityId }))

        val metadata = linkedMapOf(
            "KiwiDroid MW station formats" to "v1",
            "source" to "English Wikipedia {{Infobox radio station}} 'format', articles located via Wikidata (P1400 FCC Facility ID; P2317 call sign)",
            "license" to "CC BY-SA 4.0 (https://creativecommons.org/licenses/by-sa/4.0/); text by Wikipedia contributors; article titles listed per row",
            "fetched" to Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(),
            "stations" to "${rows.size} with format of ${db.stations.size}; $noFormat articles without a format; $frequencyMismatch non-US call matches rejected or unreadable",
        )
        out.bufferedWriter().use { MwStationDb.writeFormats(rows, metadata, it) }
        println("Wrote ${rows.size} formats to ${out.path} ($noFormat articles lacked a format; $frequencyMismatch rejected)")
    }
}
