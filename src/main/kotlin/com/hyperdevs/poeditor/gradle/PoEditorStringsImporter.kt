/*
 * Copyright 2021 HyperDevs
 *
 * Copyright 2020 BQ
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hyperdevs.poeditor.gradle

import com.hyperdevs.poeditor.gradle.ktx.downloadUrlToString
import com.hyperdevs.poeditor.gradle.network.PoEditorApiControllerImpl
import com.hyperdevs.poeditor.gradle.network.api.PoEditorApi
import com.hyperdevs.poeditor.gradle.utils.DateJsonAdapter
import com.hyperdevs.poeditor.gradle.utils.TABLET_REGEX_STRING
import com.hyperdevs.poeditor.gradle.utils.logger
import com.hyperdevs.poeditor.gradle.xml.AndroidXmlWriter
import com.hyperdevs.poeditor.gradle.xml.XmlPostProcessor
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Main class that does the XML download, parsing and saving from PoEditor files.
 */
object PoEditorStringsImporter {
    private const val POEDITOR_API_URL = "https://poeditor.com/api/"

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .add(Date::class.java, DateJsonAdapter())
        .build()

    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L
    private const val TRANSLATION_PERCENTAGE_MINIMUM = 85

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor(object : HttpLoggingInterceptor.Logger {
            override fun log(message: String) {
                logger.debug(message)
            }
        })
            .setLevel(HttpLoggingInterceptor.Level.HEADERS))
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(POEDITOR_API_URL.toHttpUrl())
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val poEditorApi: PoEditorApi = retrofit.create(PoEditorApi::class.java)

    private val xmlPostProcessor = XmlPostProcessor()

    private val xmlWriter = AndroidXmlWriter()

    fun importPoEditorStrings(apiToken: String,
                              projectId: Int,
                              defaultLang: String,
                              resDirPath: String,
                              tags: List<String>?,
                              languageValuesOverridePathMap: Map<String, String>?) {
        try {
            val poEditorApiController = PoEditorApiControllerImpl(apiToken, poEditorApi)

            // Retrieve available languages from PoEditor
            logger.lifecycle("Retrieving project languages...")
            val projectLanguages = poEditorApiController.getProjectLanguages(projectId)
            val skippedLanguages = mutableListOf<String>()

            // Iterate over every available language
            logger.lifecycle("Available languages: [${projectLanguages.joinToString(", ") { it.code }}]")
            logger.lifecycle("Will skip languages translated under $TRANSLATION_PERCENTAGE_MINIMUM%")

            projectLanguages.forEach { languageData ->
                val languageCode = languageData.code
                val percentage = languageData.percentage

                if (percentage < TRANSLATION_PERCENTAGE_MINIMUM) {
                    skippedLanguages.add("$languageCode ($percentage%)")
                    return@forEach
                }

                // Retrieve translation file URL for the given language and for the "android_strings" type,
                // acknowledging passed tags if present
                logger.lifecycle("Retrieving translation file URL for language code: $languageCode")
                val translationFileUrl = poEditorApiController.getTranslationFileUrl(
                    projectId = projectId,
                    code = languageCode,
                    filters = "translated",
                    type = "android_strings",
                    tags = tags)

                // Download translation File to in-memory string
                logger.lifecycle("Downloading file from URL: $translationFileUrl")
                val translationFile = okHttpClient.downloadUrlToString(translationFileUrl)

                // Extract final files from downloaded translation XML
                val postProcessedXmlDocumentMap =
                    xmlPostProcessor.postProcessTranslationXml(
                        translationFile, listOf(TABLET_REGEX_STRING))

                xmlWriter.saveXml(
                    resDirPath,
                    postProcessedXmlDocumentMap,
                    defaultLang,
                    languageCode,
                    languageValuesOverridePathMap
                )
            }

            logger.lifecycle("Skipped the following languages due to low terms translation percentage " +
                "(threshold: $TRANSLATION_PERCENTAGE_MINIMUM%):\n${skippedLanguages.joinToString()}")
        } catch (e: Exception) {
            logger.error("An error happened when retrieving strings from project. " +
                "Please review the plug-in's input parameters and try again")
            throw e
        }
    }
}
