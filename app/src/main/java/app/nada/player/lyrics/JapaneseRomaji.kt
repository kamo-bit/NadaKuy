package app.nada.player.lyrics

import com.atilika.kuromoji.ipadic.Tokenizer
import java.text.Normalizer

/** Dictionary-backed Japanese readings; never use Han-to-Latin transliteration for kanji. */
object JapaneseRomaji {
    private val tokenizer by lazy { Tokenizer() }
    private val kana = ("ア イ ウ エ オ カ キ ク ケ コ ガ ギ グ ゲ ゴ サ シ ス セ ソ ザ ジ ズ ゼ ゾ " +
        "タ チ ツ テ ト ダ ヂ ヅ デ ド ナ ニ ヌ ネ ノ ハ ヒ フ ヘ ホ バ ビ ブ ベ ボ パ ピ プ ペ ポ " +
        "マ ミ ム メ モ ヤ ユ ヨ ラ リ ル レ ロ ワ ヰ ヱ ヲ ヴ ァ ィ ゥ ェ ォ ャ ュ ョ ヵ ヶ")
        .split(' ').zip(("a i u e o ka ki ku ke ko ga gi gu ge go sa shi su se so za ji zu ze zo " +
            "ta chi tsu te to da ji zu de do na ni nu ne no ha hi fu he ho ba bi bu be bo pa pi pu pe po " +
            "ma mi mu me mo ya yu yo ra ri ru re ro wa i e o vu a i u e o ya yu yo ka ke").split(' ')).toMap()
    private val pairs = buildMap {
        mapOf("キ" to "ky", "ギ" to "gy", "シ" to "sh", "ジ" to "j", "チ" to "ch", "ヂ" to "j",
            "ニ" to "ny", "ヒ" to "hy", "ビ" to "by", "ピ" to "py", "ミ" to "my", "リ" to "ry").forEach { (start, roman) ->
            listOf("ャ" to "a", "ュ" to "u", "ョ" to "o").forEach { (small, vowel) -> put(start + small, roman + vowel) }
        }
        putAll(mapOf("シェ" to "she", "ジェ" to "je", "チェ" to "che", "ティ" to "ti", "ディ" to "di",
            "トゥ" to "tu", "ドゥ" to "du", "ファ" to "fa", "フィ" to "fi", "フェ" to "fe", "フォ" to "fo",
            "ウィ" to "wi", "ウェ" to "we", "ウォ" to "wo", "ヴァ" to "va", "ヴィ" to "vi", "ヴェ" to "ve", "ヴォ" to "vo"))
    }

    fun hasJapanese(text: String): Boolean = text.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }
    private fun hasKana(text: String): Boolean = text.any { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' || it in 'ｦ'..'ﾝ' }

    internal fun kanaToRomaji(value: String): String {
        val text = Normalizer.normalize(value, Normalizer.Form.NFKC).map { if (it in 'ぁ'..'ゖ') (it.code + 0x60).toChar() else it }.joinToString("")
        return buildString {
            var index = 0
            var doubled = false
            while (index < text.length) {
                val character = text[index]
                if (character == 'ッ') { doubled = true; index++; continue }
                if (character == 'ー') { lastOrNull { it in "aeiou" }?.let(::append); index++; continue }
                val pair = text.substring(index, minOf(index + 2, text.length))
                var roman = pairs[pair]
                val width = if (roman != null) 2 else 1
                if (roman == null) roman = kana[character.toString()] ?: when (character) {
                    'ン' -> if (index + 1 < text.length && kana[text[index + 1].toString()]?.firstOrNull()?.let { it in "aeiouy" } == true) "n'" else "n"
                    '、' -> ","; '。' -> "."; '！' -> "!"; '？' -> "?"; '「', '」' -> "\""
                    else -> character.toString()
                }
                if (doubled) {
                    if (roman.startsWith("ch")) append('t') else roman.firstOrNull()?.takeIf { it in "bcdfghjklmnpqrstvwxyz" }?.let(::append)
                    doubled = false
                }
                append(roman)
                index += width
            }
        }
    }

    @Synchronized
    fun romanize(text: String): String? {
        if (!hasJapanese(text)) return null
        val tokens = tokenizer.tokenize(text)
        val words = mutableListOf<String>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val surface = token.surface
            val next = tokens.getOrNull(index + 1)
            val previous = tokens.getOrNull(index - 1)
            // IPADIC splits these irregular counters into number + ニン. Read
            // the whole counter, but never rewrite compounds or 21人 as 20ひとり.
            val number = Normalizer.normalize(surface, Normalizer.Form.NFKC)
            if (token.partOfSpeechLevel2 == "数" && previous?.partOfSpeechLevel2 != "数" &&
                next?.surface == "人" && next.partOfSpeechLevel3 == "助数詞" && number in setOf("一", "1", "二", "2")) {
                words += if (number == "一" || number == "1") "ヒトリ" else "フタリ"
                index += 2
                continue
            }
            val reading = when {
                surface == "こんにちは" -> "konnichiwa"
                surface == "こんばんは" -> "konbanwa"
                token.partOfSpeechLevel1 == "助詞" && surface == "は" -> "wa"
                token.partOfSpeechLevel1 == "助詞" && surface == "へ" -> "e"
                token.partOfSpeechLevel1 == "助詞" && surface == "を" -> "o"
                else -> token.pronunciation.takeUnless { it == "*" || it.isNullOrBlank() }
                    ?: token.reading.takeUnless { it == "*" || it.isNullOrBlank() } ?: surface
            }
            // Convert after grouping inflections: 歌っ+た / 歩い+た / 見つけ+られる.
            // Spacing and gemination must follow the token sequence, not repairs
            // to an already flattened Latin string.
            val inflection = previous?.partOfSpeechLevel1 in setOf("動詞", "形容詞", "助動詞") &&
                (token.partOfSpeechLevel1 == "助動詞" ||
                    (token.partOfSpeechLevel1 == "動詞" && token.partOfSpeechLevel2 in setOf("非自立", "接尾")) ||
                    (token.partOfSpeechLevel2 == "接続助詞" && surface in setOf("て", "で", "ば")))
            val pendingConsonant = words.lastOrNull()?.lastOrNull() in listOf('ッ', 'っ') &&
                reading.firstOrNull()?.let { it in 'ぁ'..'ゖ' || it in 'ァ'..'ヺ' } == true
            if (words.isNotEmpty() && (inflection || pendingConsonant)) {
                words[words.lastIndex] += reading
            } else words += reading
            index++
        }
        return words.map(::kanaToRomaji).filter(String::isNotBlank).joinToString(" ").replace(Regex("\\s+([,.!?])"), "$1").trim()
            .takeIf { it.isNotBlank() && it != text }
    }

    fun convert(document: LyricsDocument): LyricsDocument {
        // Han characters alone cannot distinguish Japanese from Chinese lyrics.
        if (document.lines.none { hasKana(it.text) } && !hasKana(document.title) && !hasKana(document.artist)) return document
        val lines = document.lines.map { it.copy(romaji = romanize(it.text)) }
        return document.copy(lines = lines, romanized = lines.any { it.romaji != null })
    }
}
