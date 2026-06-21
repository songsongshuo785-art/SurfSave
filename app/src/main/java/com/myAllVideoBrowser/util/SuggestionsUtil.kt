package com.myAllVideoBrowser.util

import android.net.Uri
import com.myAllVideoBrowser.data.local.model.Suggestion
import io.reactivex.rxjava3.core.BackpressureStrategy
import io.reactivex.rxjava3.core.Flowable
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

class SuggestionsUtils {
    companion object {
        fun getSuggestions(okHttpClient: OkHttpClient, input: String): Flowable<List<Suggestion>> {
            val query = input.trim()
            if (query.isBlank()) {
                return Flowable.just(emptyList())
            }

            val locale = Locale.getDefault()
            if (locale.language.equals("zh", ignoreCase = true)) {
                return Flowable.create({ emitter ->
                    val result = runCatching {
                        val request = Request.Builder()
                            .url(buildBingSuggestionUrl(query, locale))
                            .build()
                        val response = okHttpClient.newCall(request).execute()
                            .use { response -> response.body?.string().orEmpty() }
                        parseBingSuggestions(response, query)
                    }.getOrElse {
                        buildLocalSuggestions(query)
                    }

                    emitter.onNext(result)
                    emitter.onComplete()
                }, BackpressureStrategy.LATEST)
            }

            return Flowable.create({ emitter ->
                val fallback = buildLocalSuggestions(query)
                val result = runCatching {
                    val request = Request.Builder()
                        .url(
                            "https://duckduckgo.com/ac/?q=${
                                URLEncoder.encode(query, Charsets.UTF_8.name())
                            }&kl=wt-wt"
                        )
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                        .use { response -> response.body?.string().orEmpty() }

                    val suggestions: ArrayList<Suggestion> = ArrayList()
                    val json = JSONArray(response)
                    for (i in 0 until json.length()) {
                        runCatching {
                            val phraseObj = JSONObject(json.get(i).toString())
                            val phrase = phraseObj.get("phrase").toString().trim()
                            if (phrase.isNotBlank()) {
                                suggestions.add(Suggestion(content = phrase))
                            }
                        }
                    }
                    if (suggestions.isEmpty()) {
                        fallback
                    } else {
                        suggestions
                    }
                }.getOrElse {
                    fallback
                }

                emitter.onNext(result)
                emitter.onComplete()
            }, BackpressureStrategy.LATEST)
        }

        private fun buildBingSuggestionUrl(input: String, locale: Locale): String {
            val market = when (locale.country.uppercase(Locale.US)) {
                "TW" -> "zh-TW"
                "HK", "MO" -> "zh-HK"
                else -> "zh-CN"
            }
            return String.format(
                Locale.US,
                "https://www.bing.com/complete/search?mkt=%s&q=%s&format=json",
                market,
                Uri.encode(input)
            )
        }

        private fun parseBingSuggestions(response: String, fallbackInput: String): List<Suggestion> {
            val suggestions = ArrayList<Suggestion>()
            val json = JSONArray(response)
            val candidateArray = json.optJSONArray(1)
            if (candidateArray != null) {
                for (i in 0 until candidateArray.length()) {
                    val phrase = candidateArray.optString(i).trim()
                    if (phrase.isNotBlank()) {
                        suggestions.add(Suggestion(content = phrase))
                    }
                }
            }
            return if (suggestions.isEmpty()) buildLocalSuggestions(fallbackInput) else suggestions
        }

        private fun buildLocalSuggestions(input: String): List<Suggestion> {
            return listOf(Suggestion(content = input))
        }
    }
}
