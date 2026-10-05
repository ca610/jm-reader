package com.jm.reader.data.repo

import com.jm.reader.data.model.BlogItem
import com.jm.reader.data.model.Category
import com.jm.reader.data.model.CategoryRef
import com.jm.reader.data.model.ComicDetail
import com.jm.reader.data.model.ComicListItem
import com.jm.reader.data.model.ForumItem
import com.jm.reader.data.model.GameItem
import com.jm.reader.data.model.Member
import com.jm.reader.data.model.MovieItem
import com.jm.reader.data.model.NovelItem
import com.jm.reader.data.model.ReadData
import com.jm.reader.data.model.TagItem
import com.jm.reader.data.model.bool
import com.jm.reader.data.model.long
import com.jm.reader.data.model.obj
import com.jm.reader.data.model.objList
import com.jm.reader.data.model.str
import com.jm.reader.data.model.strList
import com.jm.reader.data.model.strOrNull
import com.jm.reader.data.net.ApiClient
import com.jm.reader.data.net.HostManager
import com.jm.reader.data.session.SessionManager
import org.json.JSONArray
import org.json.JSONObject

/** Unified result type used across repositories. */
sealed class RepoResult<out T> {
    data class Ok<T>(val data: T) : RepoResult<T>()
    data class Err(val message: String) : RepoResult<Nothing>()

    fun <R> map(fn: (T) -> R): RepoResult<R> = when (this) {
        is Ok -> Ok(fn(data))
        is Err -> this
    }
}

/** One page of `search` results: the comics plus the API-reported total and the search scope. */
data class SearchPage(
    val query: String = "",
    val searchType: String = "site",
    val total: Int = 0,
    val items: List<ComicListItem> = emptyList(),
)

/**
 * Thin wrapper over [ApiClient] exposing typed methods for every feature used by the app.
 * Ads, coin purchases and recharge flows are intentionally NOT exposed here.
 */
class AppRepository(
    private val session: SessionManager,
    private val api: ApiClient,
    private val hostManager: HostManager,
) {
    companion object {
        /**
         * Returned by [login] when the server rejects the username / password pair. Screens map it
         * to a localised message instead of surfacing the raw API failure.
         */
        const val ERR_BAD_CREDENTIALS = "jm.err.bad_credentials"
    }

    // -----------------------------------------------------------------------
    // Bootstrap
    // -----------------------------------------------------------------------

    /** Ensures an API host is configured, then refreshes app settings (img_host). */
    suspend fun bootstrap(): RepoResult<String> {
        hostManager.bootstrap()
        if (session.apiUrl.isNullOrBlank()) return RepoResult.Err("無法取得 API 主機")
        refreshSettings()
        return RepoResult.Ok(session.apiUrl!!)
    }

    /** GET /setting - stores img_host (cover CDN) into the session. */
    suspend fun refreshSettings() {
        val r = api.get("setting", mapOf("app_img_shunt" to "1", "t" to System.currentTimeMillis() / 1000))
        if (r is ApiClient.Result.Success) {
            r.obj?.let { session.imgHost = it.str("img_host") }
        }
    }

    // -----------------------------------------------------------------------
    // Home / comic lists
    // -----------------------------------------------------------------------

    /** GET /promote - returns a list of promote sections. */
    suspend fun getPromote(): RepoResult<List<List<ComicListItem>>> {
        val r = api.get("promote")
        return r.toRepoList { o, arr ->
            (arr ?: JSONArray()).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { sec ->
                    (sec.optJSONArray("content") ?: JSONArray()).let { content ->
                        (0 until content.length()).mapNotNull { content.optJSONObject(it)?.let { ComicListItem.fromJson(it) } }
                            .distinctBy { it.id }
                    }
                } }
            }
        }
    }

    /** GET /latest?page= */
    suspend fun getLatest(page: Int): RepoResult<List<ComicListItem>> =
        api.get("latest", mapOf("page" to page)).toRepoList { _, arr ->
            (arr ?: JSONArray()).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { ComicListItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }

    /** GET /promote_list?id=&page= */
    suspend fun getPromoteList(id: String, page: Int): RepoResult<List<ComicListItem>> =
        api.get("promote_list", mapOf("id" to id, "page" to page)).toRepoList { o, arr ->
            listFromObjOrArr(o, arr)
        }

    /** GET /serialization?type=&date=&page= */
    suspend fun getSerializationMore(type: String?, date: Long?, page: Int): RepoResult<List<ComicListItem>> =
        api.get("serialization", mapOf("type" to type, "date" to date, "page" to page)).toRepoList { o, arr ->
            listFromObjOrArr(o, arr)
        }

    /** GET /week - returns the raw config (categories/type tabs). */
    suspend fun getWeek(): RepoResult<JSONObject> =
        api.get("week").toRepoObj()

    /** GET /week/filter?id=&type= */
    suspend fun getWeekFilter(id: String, type: String): RepoResult<List<ComicListItem>> =
        api.get("week/filter", mapOf("id" to id, "type" to type)).toRepoList { o, arr ->
            listFromObjOrArr(o, arr)
        }

    /** GET /daily?user_id= */
    suspend fun getDaily(userId: String): RepoResult<JSONObject> =
        api.get("daily", mapOf("user_id" to userId)).toRepoObj()

    /** GET /daily_list?user_id= */
    suspend fun getDailyList(userId: String): RepoResult<JSONObject> =
        api.get("daily_list", mapOf("user_id" to userId)).toRepoObj()

    /** POST /daily_chk {user_id, daily_id} */
    suspend fun dailyCheck(userId: String, dailyId: String): RepoResult<JSONObject> =
        api.post("daily_chk", mapOf("user_id" to userId, "daily_id" to dailyId)).toRepoObj()

    /** POST /daily_list/filter {data} */
    suspend fun dailyListFilter(data: String): RepoResult<JSONObject> =
        api.post("daily_list/filter", mapOf("data" to data)).toRepoObj()

    // -----------------------------------------------------------------------
    // Search / tags / categories
    // -----------------------------------------------------------------------

    /**
     * GET /search - keyword search.
     *
     * @param searchType scope of the search: "site" (default, everything), "work" (titles only)
     *        or "author" (author names only). The API echoes the scope it actually used.
     * @param filter optional JSON string for the advanced filter.
     */
    suspend fun search(
        keyword: String,
        filter: String? = null,
        page: Int = 1,
        searchType: String? = null,
    ): RepoResult<List<ComicListItem>> = searchPage(keyword, searchType, page, filter).map { it.items }

    /**
     * GET /search - like [search] but keeps the API-reported `total` (and echoed `search_type`)
     * so the UI can show result counts and know when paging is exhausted.
     */
    suspend fun searchPage(
        keyword: String,
        searchType: String? = null,
        page: Int = 1,
        filter: String? = null,
    ): RepoResult<SearchPage> {
        val params = mutableMapOf<String, Any>("search_query" to keyword, "page" to page)
        if (!filter.isNullOrBlank()) params["filter"] = filter
        if (!searchType.isNullOrBlank()) params["search_type"] = searchType
        return api.get("search", params).toRepoList { o, arr ->
            SearchPage(
                query = o?.str("search_query")?.ifBlank { keyword } ?: keyword,
                searchType = o?.str("search_type").orEmpty().ifBlank { searchType ?: "site" },
                total = o?.long("total")?.toInt() ?: 0,
                items = parseComicList(o, arr),
            )
        }
    }

    /** GET /hot_tags */
    suspend fun hotTags(): RepoResult<List<TagItem>> =
        api.get("hot_tags").toRepoList { o, arr ->
            val list = (arr ?: o?.optJSONArray("data") ?: JSONArray())
            (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let { TagItem.fromJson(it) } }
        }

    /** GET /random_recommend */
    suspend fun randomRecommend(): RepoResult<List<ComicListItem>> =
        api.get("random_recommend").toRepoList { o, arr -> listFromObjOrArr(o, arr) }

    /** GET /categories - response is {categories: [...], blocks: [...]}. */
    suspend fun categories(): RepoResult<List<Category>> =
        api.get("categories").toRepoList { o, arr ->
            objArrFromResponse(o, arr, listOf("categories")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { Category.fromJson(it) } }
                    .distinctBy { it.slug }
            }
        }

    /** GET /categories/filter?c=&o=&page= - c is the category slug, o the sort key. */
    suspend fun categoriesFilter(c: String, o: String = "", page: Int = 1): RepoResult<List<ComicListItem>> =
        api.get("categories/filter", mapOf("c" to c, "o" to o, "page" to page)).toRepoList { o2, arr ->
            listFromObjOrArr(o2, arr)
        }

    // -----------------------------------------------------------------------
    // Comic detail / reader
    // -----------------------------------------------------------------------

    /** GET /album?id= */
    suspend fun getAlbum(id: String): RepoResult<ComicDetail> =
        api.get("album", mapOf("id" to id)).toRepoList { o, _ ->
            ComicDetail.fromJson(o ?: JSONObject())
        }

    /** GET /comic_read?id= */
    suspend fun comicRead(id: String): RepoResult<ReadData> =
        api.get("comic_read", mapOf("id" to id)).toRepoList { o, _ ->
            ReadData.fromJson(o ?: JSONObject())
        }

    /** GET /album_download_2/{id} */
    suspend fun albumDownload(id: String): RepoResult<JSONObject> =
        api.get("album_download_2/$id").toRepoObj()

    /** GET /hot_tags */
    suspend fun hotTagsV2(): RepoResult<List<TagItem>> = hotTags()

    // -----------------------------------------------------------------------
    // Auth / member
    // -----------------------------------------------------------------------

    /**
     * POST /login {username, password}
     *
     * Rejected credentials come back as HTTP 401 with an empty payload; a successful login
     * returns the member payload. The mobile API never sends a `jwttoken` - the member's `s`
     * field becomes the AVS cookie that authenticates every later request, so that payload is
     * what makes [SessionManager.isLoggedIn] true.
     */
    suspend fun login(username: String, password: String): RepoResult<Member> {
        val r = api.post("login", mapOf("username" to username, "password" to password))
        return when (r) {
            is ApiClient.Result.Success -> {
                val data = r.obj
                val memberPayload = data != null &&
                    (data.str("s").isNotBlank() || data.str("uid").isNotBlank() || data.str("username").isNotBlank())
                if (r.code == 200 && data != null && memberPayload) {
                    session.saveAuth(data.str("jwttoken"), data)
                    RepoResult.Ok(Member.fromJson(data))
                } else {
                    RepoResult.Err(data?.str("msg").orEmpty().ifBlank { ERR_BAD_CREDENTIALS })
                }
            }
            is ApiClient.Result.ApiFailure ->
                RepoResult.Err(if (r.code == 401) ERR_BAD_CREDENTIALS else "登入失敗 (${r.code})")
            is ApiClient.Result.NetworkFailure -> RepoResult.Err(r.message)
        }
    }

    /** POST /register {username,password,password_confirm,email,gender} */
    suspend fun register(
        username: String,
        password: String,
        passwordConfirm: String,
        email: String,
        gender: String,
    ): RepoResult<JSONObject> {
        val r = api.post(
            "register",
            mapOf(
                "username" to username,
                "password" to password,
                "password_confirm" to passwordConfirm,
                "email" to email,
                "gender" to gender,
            ),
        )
        return when (r) {
            is ApiClient.Result.Success -> {
                val failure = registrationFailure(r.obj)
                if (r.code == 200 && failure == null) RepoResult.Ok(r.obj ?: JSONObject())
                else RepoResult.Err(failure ?: "註冊失敗 (${r.code})")
            }
            is ApiClient.Result.ApiFailure -> RepoResult.Err("註冊失敗 (${r.code})")
            is ApiClient.Result.NetworkFailure -> RepoResult.Err(r.message)
        }
    }

    /**
     * `/register` answers HTTP 200 even when it refuses the submission, reporting
     * `{ "status": "fail", "msg": "...", "errors": ["..."] }`. Returns the reported problem,
     * or null when the registration went through.
     */
    private fun registrationFailure(obj: JSONObject?): String? {
        val o = obj ?: return null
        val errors = o.optJSONArray("errors")
        val status = o.str("status").lowercase()
        val failed = (errors?.length() ?: 0) > 0 ||
            status == "fail" || status == "error" || status == "false"
        if (!failed) return null
        return (0 until (errors?.length() ?: 0))
            .mapNotNull { errors?.optString(it)?.takeIf { m -> m.isNotBlank() } }
            .joinToString("\n")
            .ifBlank { o.str("msg") }
            .ifBlank { "註冊失敗" }
    }

    /** POST /forgot {email} */
    suspend fun forgot(email: String): RepoResult<JSONObject> =
        api.post("forgot", mapOf("email" to email)).toRepoObj()

    /** POST /logout */
    suspend fun logout(): RepoResult<JSONObject> =
        api.post("logout").toRepoObj()

    /** GET /useredit/{uid} */
    suspend fun getUserInfo(uid: String): RepoResult<JSONObject> =
        api.get("useredit/$uid").toRepoObj()

    /** POST /useredit/{uid} */
    suspend fun editUser(uid: String, form: Map<String, Any?>): RepoResult<JSONObject> =
        api.post("useredit/$uid", form).toRepoObj()

    // -----------------------------------------------------------------------
    // Library / favorites / history / likes
    // -----------------------------------------------------------------------

    /** GET /favorite?page=&folder_id=&o= (o: "mr" favorite time / "mp" update time). */
    suspend fun favorites(page: Int, folderId: String = "", o: String = "mr"): RepoResult<List<ComicListItem>> =
        api.get("favorite", mapOf("page" to page, "folder_id" to folderId, "o" to o)).toRepoList { o2, arr ->
            listFromObjOrArr(o2, arr)
        }

    /** POST /favorite {aid} */
    suspend fun addFavorite(aid: String): RepoResult<JSONObject> =
        api.post("favorite", mapOf("aid" to aid)).toRepoObj()

    /** POST /favorite_folder - create/edit/delete favorite folders. */
    suspend fun editFavoriteFolder(type: String, folderId: String? = null, folderName: String? = null, aid: String? = null): RepoResult<JSONObject> {
        val p = mutableMapOf<String, Any>("type" to type)
        if (!folderId.isNullOrBlank()) p["folder_id"] = folderId
        if (!folderName.isNullOrBlank()) p["folder_name"] = folderName
        if (!aid.isNullOrBlank()) p["aid"] = aid
        return api.post("favorite_folder", p).toRepoObj()
    }

    /** POST /like {id, like_type} */
    suspend fun addLike(id: String, likeType: String = "album"): RepoResult<JSONObject> =
        api.post("like", mapOf("id" to id, "like_type" to likeType)).toRepoObj()

    /** GET /tags_favorite - response items are {tag, updated_at}. */
    suspend fun tagsFavorite(): RepoResult<List<TagItem>> =
        api.get("tags_favorite").toRepoList { o, arr ->
            objArrFromResponse(o, arr, listOf("list")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { TagItem.fromJson(it) } }
            }
        }

    /** POST /tags_favorite_update {type, tags} */
    suspend fun updateTagsFavorite(type: String, tags: String): RepoResult<JSONObject> =
        api.post("tags_favorite_update", mapOf("type" to type, "tags" to tags)).toRepoObj()

    // -----------------------------------------------------------------------
    // History (watch list)
    // -----------------------------------------------------------------------

    /** GET /watch_list?page= */
    suspend fun watchList(page: Int): RepoResult<JSONObject> =
        api.get("watch_list", mapOf("page" to page)).toRepoObj()

    /** POST /watch_list {id} - marks an album as read. */
    suspend fun addWatch(id: String): RepoResult<JSONObject> =
        api.post("watch_list", mapOf("id" to id)).toRepoObj()

    /** POST /album_sertracking {id} - track a comic for updates. */
    suspend fun trackAlbum(id: String): RepoResult<JSONObject> =
        api.post("album_sertracking", mapOf("id" to id)).toRepoObj()

    /** POST /album_tracking {page} - tracked comics list. */
    suspend fun trackedAlbums(page: Int): RepoResult<JSONObject> =
        api.post("album_tracking", mapOf("page" to page)).toRepoObj()

    // -----------------------------------------------------------------------
    // Notifications
    // -----------------------------------------------------------------------

    /** GET /notifications?type=&page= */
    suspend fun notifications(page: Int): RepoResult<JSONObject> =
        api.get("notifications", mapOf("page" to page)).toRepoObj()

    /** GET /notifications/unreadCount */
    suspend fun notificationsUnread(): RepoResult<JSONObject> =
        api.get("notifications/unreadCount").toRepoObj()

    // -----------------------------------------------------------------------
    // Novels
    // -----------------------------------------------------------------------

    /** GET /novels - params {o, t} (o: "" latest / mv / mp / tf; t: "a" content type). */
    suspend fun novels(o: String = "", t: String = "a"): RepoResult<List<NovelItem>> =
        api.get("novels", mapOf("o" to o, "t" to t)).toRepoList { o2, arr ->
            objArrFromResponse(o2, arr, listOf("list")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { NovelItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }

    /** GET /novel - params {nid}. */
    suspend fun novelDetail(nid: String): RepoResult<JSONObject> =
        api.get("novel", mapOf("nid" to nid)).toRepoObj()

    /** GET /novelchapters - params {ncid}. */
    suspend fun novelChapters(ncid: String): RepoResult<JSONObject> =
        api.get("novelchapters", mapOf("ncid" to ncid)).toRepoObj()

    /** GET /search_novels?search_query= */
    suspend fun searchNovels(keyword: String): RepoResult<List<NovelItem>> =
        api.get("search_novels", mapOf("search_query" to keyword)).toRepoList { o2, arr ->
            objArrFromResponse(o2, arr, listOf("list")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { NovelItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }

    /** GET /novel_favorites - params {page, folder_id, o}. */
    suspend fun novelFavorites(page: Int = 1, folderId: String = "", o: String = "mr"): RepoResult<JSONObject> =
        api.get("novel_favorites", mapOf("page" to page, "folder_id" to folderId, "o" to o)).toRepoObj()

    // -----------------------------------------------------------------------
    // Movies
    // -----------------------------------------------------------------------

    /** GET /videos - params {page, video_type?}. */
    suspend fun movies(page: Int = 1, videoType: String = "movie"): RepoResult<List<MovieItem>> =
        api.get("videos", mapOf("page" to page, "video_type" to videoType)).toRepoList { o, arr ->
            objArrFromResponse(o, arr, listOf("list")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { MovieItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }

    /** GET /latest_hanime */
    suspend fun latestHanime(): RepoResult<List<MovieItem>> =
        api.get("latest_hanime").toRepoList { o, arr ->
            val ja = arr ?: JSONArray()
            (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { MovieItem.fromJson(it) } }
                .distinctBy { it.id }
        }

    /** GET /video - params {id, video_type}. */
    suspend fun movieInfo(id: String, videoType: String = "movie"): RepoResult<JSONObject> =
        api.get("video", mapOf("id" to id, "video_type" to videoType)).toRepoObj()

    // -----------------------------------------------------------------------
    // Games
    // -----------------------------------------------------------------------

    /** GET /allgames - params {page, search?, category?, game_type?}. Response `games` array. */
    suspend fun games(page: Int = 1): RepoResult<List<GameItem>> =
        api.get("allgames", mapOf("page" to page)).toRepoList { o, arr ->
            objArrFromResponse(o, arr, listOf("games", "hot_games")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { GameItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }

    /** GET /game/{id} */
    suspend fun gameInfo(id: String): RepoResult<JSONObject> =
        api.get("game/$id").toRepoObj()

    // -----------------------------------------------------------------------
    // Blogs / forum
    // -----------------------------------------------------------------------

    /** GET /blogs - params {page, blog_type}. */
    suspend fun blogs(page: Int = 1, blogType: String = "dinner"): RepoResult<List<BlogItem>> =
        api.get("blogs", mapOf("page" to page, "blog_type" to blogType)).toRepoList { o, arr ->
            objArrFromResponse(o, arr, listOf("list")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { BlogItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }

    /** GET /blog?id= */
    suspend fun blogInfo(id: String): RepoResult<JSONObject> =
        api.get("blog", mapOf("id" to id)).toRepoObj()

    /** GET /forum - params {mode, page}. */
    suspend fun forum(mode: String = "all", page: Int = 1): RepoResult<List<ForumItem>> =
        api.get("forum", mapOf("mode" to mode, "page" to page)).toRepoList { o, arr ->
            objArrFromResponse(o, arr, listOf("list")).let { ja ->
                (0 until ja.length()).mapNotNull { ja.optJSONObject(it)?.let { ForumItem.fromJson(it) } }
                    .distinctBy { it.cid }
            }
        }

    /** POST /comment - send a comment/topic. */
    suspend fun sendComment(params: Map<String, Any?>): RepoResult<JSONObject> =
        api.post("comment", params).toRepoObj()

    /** POST /comment_vote */
    suspend fun voteComment(id: String): RepoResult<JSONObject> =
        api.post("comment_vote", mapOf("id" to id)).toRepoObj()

    // -----------------------------------------------------------------------
    // Tasks / achievements (kept - not ad related)
    // -----------------------------------------------------------------------

    /** GET /tasks?type=&filter= */
    suspend fun tasks(type: String, filter: String? = null): RepoResult<JSONObject> =
        api.get("tasks", mapOf("type" to type, "filter" to filter)).toRepoObj()

    // -----------------------------------------------------------------------
    // Creator
    // -----------------------------------------------------------------------

    /** GET /creator_author */
    suspend fun creators(): RepoResult<JSONObject> =
        api.get("creator_author").toRepoObj()

    /** GET /creator_work?uid= */
    suspend fun creatorWorks(uid: String): RepoResult<JSONObject> =
        api.get("creator_work", mapOf("uid" to uid)).toRepoObj()

    // -----------------------------------------------------------------------
    // Helper adapters
    // -----------------------------------------------------------------------

    private inline fun <T> ApiClient.Result.toRepoList(fn: (JSONObject?, JSONArray?) -> T): RepoResult<T> =
        when (this) {
            is ApiClient.Result.Success ->
                if (code == 200) RepoResult.Ok(fn(obj, arr))
                else RepoResult.Err("API 錯誤 ($code)")
            is ApiClient.Result.ApiFailure -> RepoResult.Err("API 錯誤 (${code})${message?.let { ": $it" } ?: ""}")
            is ApiClient.Result.NetworkFailure -> RepoResult.Err(message)
        }

    private fun ApiClient.Result.toRepoObj(): RepoResult<JSONObject> =
        when (this) {
            is ApiClient.Result.Success ->
                if (code == 200 && obj != null) RepoResult.Ok(obj)
                else if (code != 200) RepoResult.Err("API 錯誤 ($code)")
                else RepoResult.Err("無資料")
            is ApiClient.Result.ApiFailure -> RepoResult.Err("API 錯誤 (${code})${message?.let { ": $it" } ?: ""}")
            is ApiClient.Result.NetworkFailure -> RepoResult.Err(message)
        }

    private fun listFromObjOrArr(o: JSONObject?, arr: JSONArray?): List<ComicListItem> {
        if (arr != null && arr.length() > 0) {
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { ComicListItem.fromJson(it) } }
                .distinctBy { it.id }
        }
        val data = o?.obj("data")
        val candidates = listOf(
            data?.optJSONArray("content"),
            data?.optJSONArray("list"),
            o?.optJSONArray("content"),
            o?.optJSONArray("list"),
        )
        for (c in candidates) {
            if (c != null && c.length() > 0) {
                return (0 until c.length()).mapNotNull { c.optJSONObject(it)?.let { ComicListItem.fromJson(it) } }
                    .distinctBy { it.id }
            }
        }
        return emptyList()
    }

    /** Reads a comic list from either a bare JSON array or a `{ content | list }` wrapper. */
    private fun parseComicList(o: JSONObject?, arr: JSONArray?): List<ComicListItem> {
        val list = o?.obj("data") ?: o
        if (list != null) {
            val content = list.objList("content")
            if (content.isNotEmpty()) return content.map { ComicListItem.fromJson(it) }.distinctBy { it.id }
            val rows = list.objList("list")
            if (rows.isNotEmpty()) return rows.map { ComicListItem.fromJson(it) }.distinctBy { it.id }
            return emptyList()
        }
        return listFromObjOrArr(o, arr)
    }

    /** Extracts a JSON array of objects from the response for non-comic sections. */
    private fun objArrFromResponse(o: JSONObject?, arr: JSONArray?, keys: List<String>): JSONArray {
        if (arr != null) return arr
        o?.let {
            for (key in keys) {
                it.optJSONArray(key)?.let { ja -> if (ja.length() > 0) return ja }
                it.obj("data")?.optJSONArray(key)?.let { ja -> if (ja.length() > 0) return ja }
            }
        }
        return JSONArray()
    }

    // -----------------------------------------------------------------------
    // Image URL helpers
    // -----------------------------------------------------------------------

    /** Cover URL for comic list/grid items. */
    fun comicCover(id: String, updateAt: Long): String {
        val host = session.imgHost.ifBlank { "https://cdn-msp3.jmdanjonproxy.vip" }
        return "$host/media/albums/${id}_3x4.jpg?v=$updateAt"
    }

    /** Cover URL for detail (uses addtime). */
    fun comicCoverDetail(id: String, addtime: Long): String = comicCover(id, addtime)

    /** Prefixes a relative media path with the current image host (e.g. /media/novels/x.jpg). */
    fun imgUrl(path: String): String {
        if (path.startsWith("http")) return path
        val host = session.imgHost.ifBlank { "https://cdn-msp3.jmdanjonproxy.vip" }
        return host + "/" + path.trimStart('/')
    }

    /** Current member info. */
    val member: Member?
        get() = session.memberJson?.let { json ->
            runCatching { Member.fromJson(JSONObject(json)) }.getOrNull()
        }
}
