package com.github.mytv.data.apps

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.appPrefsStore: DataStore<Preferences> by preferencesDataStore("launcher_app_prefs")

class AppPrefsRepository(private val context: Context) {
    private val hiddenKey = stringSetPreferencesKey("hidden_apps")
    private val foldersKey = stringPreferencesKey("folders")
    private val pageKey = stringPreferencesKey("page")
    private val focusKey = stringPreferencesKey("focus_tag")
    private val prefs = context.appPrefsStore

    private fun Flow<Preferences>.safe(): Flow<Preferences> =
        catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    val hiddenApps: Flow<Set<String>> = prefs.data.safe().map { it[hiddenKey] ?: emptySet() }

    val folders: Flow<List<Folder>> = prefs.data.safe().map { decodeFolders(it[foldersKey]) }

    suspend fun readPageFocus(): Pair<String?, String?> {
        val data = prefs.data.safe().first()
        return data[pageKey] to data[focusKey]
    }

    suspend fun savePageFocus(page: String, focus: String?) {
        prefs.edit {
            it[pageKey] = page
            if (focus == null) it.remove(focusKey) else it[focusKey] = focus
        }
    }

    suspend fun setHidden(apps: Set<String>) {
        prefs.edit { it[hiddenKey] = apps }
    }

    suspend fun setFolders(folders: List<Folder>) {
        prefs.edit { it[foldersKey] = encodeFolders(folders) }
    }

    private fun decodeFolders(raw: String?): List<Folder> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isEmpty() || name.isEmpty()) continue
                    val apps = item.optJSONArray("apps") ?: JSONArray()
                    add(
                        Folder(
                            id = id,
                            name = name,
                            apps = buildList {
                                for (j in 0 until apps.length()) {
                                    val app = apps.optString(j)
                                    if (app.isNotEmpty()) add(app)
                                }
                            },
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeFolders(folders: List<Folder>): String {
        val array = JSONArray()
        for (folder in folders) {
            array.put(
                JSONObject()
                    .put("id", folder.id)
                    .put("name", folder.name)
                    .put("apps", JSONArray(folder.apps)),
            )
        }
        return array.toString()
    }
}
