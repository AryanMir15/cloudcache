package com.lagradost.cloudstream3.syncproviders.providers

import androidx.annotation.StringRes
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.NextAiring
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.syncproviders.AuthData
import com.lagradost.cloudstream3.syncproviders.AuthLoginPage
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.ui.SyncWatchType
import com.lagradost.cloudstream3.ui.library.ListSorting
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.DataStore.toKotlinObject
import com.lagradost.cloudstream3.utils.DataStoreHelper.toYear
import com.lagradost.cloudstream3.utils.txt
import java.net.URLEncoder
import java.util.Locale

class AniListApi : SyncAPI() {
    override var name = "AniList"
    override val idPrefix = "anilist"

    val key = "6871"
    override val redirectUrlIdentifier = "anilistlogin"
    override var requireLibraryRefresh = true
    override val hasOAuth2 = true
    override var mainUrl = "https://anilist.co"
    override val icon = R.drawable.ic_anilist_icon
    override val createAccountUrl = "$mainUrl/signup"
    override val syncIdName = SyncIdName.Anilist

    override fun loginRequest(): AuthLoginPage? =
        AuthLoginPage("https://anilist.co/api/v2/oauth/authorize?client_id=$key&response_type=token")

    override suspend fun login(redirectUrl: String, payload: String?): AuthToken? {
        val sanitizer = splitRedirectUrl(redirectUrl)
        val token = AuthToken(
            accessToken = sanitizer["access_token"] ?: throw ErrorLoadingException("No access token"),
            //refreshToken = sanitizer["refresh_token"],
            accessTokenLifetime = unixTime + sanitizer["expires_in"]!!.toLong(),
        )
        return token
    }

    // https://docs.anilist.co/guide/auth/
    override suspend fun refreshToken(token: AuthToken): AuthToken? {
        // AniList access tokens are long-lived. They will remain valid for 1 year from the time they are issued.
        // Refresh tokens are not currently supported. Once a token expires, you will need to re-authenticate your users.
        return super.refreshToken(token)
    }

    override suspend fun user(token: AuthToken?): AuthUser? {
        val user = getUser(token ?: return null)
            ?: throw ErrorLoadingException("Unable to fetch user data")

        return AuthUser(
            id = user.id,
            name = user.name,
            profilePicture = user.picture,
        )
    }

    override fun urlToId(url: String): String? =
        url.removePrefix("$mainUrl/anime/").removeSuffix("/")


    private fun getUrlFromId(id: Int): String {
        return "$mainUrl/anime/$id"
    }

    override suspend fun search(auth : AuthData?, query: String): List<SyncAPI.SyncSearchResult>? {
        val data = searchShows(query) ?: return null
        return data.data?.page?.media?.map {
            SyncAPI.SyncSearchResult(
                it.title.romaji ?: return null,
                this.name,
                it.id.toString(),
                getUrlFromId(it.id),
                it.bannerImage
            )
        }
    }

    override suspend fun load(auth : AuthData?, id: String): SyncAPI.SyncResult? {
        val internalId = (Regex("anilist\\.co/anime/(\\d*)").find(id)?.groupValues?.getOrNull(1)
            ?: id).toIntOrNull() ?: throw ErrorLoadingException("Invalid internalId")
        val season = getSeason(internalId).data.media

        return SyncAPI.SyncResult(
            season.id.toString(),
            nextAiring = season.nextAiringEpisode?.let {
                NextAiring(
                    it.episode ?: return@let null,
                    (it.timeUntilAiring ?: return@let null) + unixTime
                )
            },
            title = season.title?.userPreferred,
            synonyms = season.synonyms,
            isAdult = season.isAdult,
            totalEpisodes = season.episodes,
            synopsis = season.description,
            actors = season.characters?.edges?.mapNotNull { edge ->
                val node = edge.node ?: return@mapNotNull null
                ActorData(
                    actor = Actor(
                        name = node.name?.userPreferred ?: node.name?.full ?: node.name?.native
                        ?: return@mapNotNull null,
                        image = node.image?.large ?: node.image?.medium
                    ),
                    role = when (edge.role) {
                        "MAIN" -> ActorRole.Main
                        "SUPPORTING" -> ActorRole.Supporting
                        "BACKGROUND" -> ActorRole.Background
                        else -> null
                    },
                    voiceActor = edge.voiceActors?.firstNotNullOfOrNull { staff ->
                        Actor(
                            name = staff.name?.userPreferred ?: staff.name?.full
                            ?: staff.name?.native
                            ?: return@mapNotNull null,
                            image = staff.image?.large ?: staff.image?.medium
                        )
                    }
                )
            },
            publicScore = Score.from100(season.averageScore),
            recommendations = season.recommendations?.edges?.mapNotNull { rec ->
                val recMedia = rec.node.mediaRecommendation
                SyncAPI.SyncSearchResult(
                    name = recMedia?.title?.userPreferred ?: return@mapNotNull null,
                    this.name,
                    recMedia.id?.toString() ?: return@mapNotNull null,
                    getUrlFromId(recMedia.id),
                    recMedia.coverImage?.extraLarge ?: recMedia.coverImage?.large
                    ?: recMedia.coverImage?.medium
                )
            },
            trailers = when (season.trailer?.site?.lowercase()?.trim()) {
                "youtube" -> listOf("https://www.youtube.com/watch?v=${season.trailer.id}")
                else -> null
            }
            //TODO REST
        )
    }

    override suspend fun status(auth : AuthData?, id: String): SyncAPI.AbstractSyncStatus? {
        val internalId = id.toIntOrNull() ?: return null
        val data = getDataAboutId(auth ?: return null, internalId) ?: return null

        return SyncAPI.SyncStatus(
            score = Score.from100(data.score),
            watchedEpisodes = data.progress,
            status = SyncWatchType.fromInternalId(data.type?.value ?: return null),
            isFavorite = data.isFavourite,
            maxEpisodes = data.episodes,
        )
    }

    override suspend fun updateStatus(
        auth: AuthData?,
        id: String,
        newStatus: AbstractSyncStatus
    ): Boolean {
        return postDataAboutId(
            auth ?: return false,
            id.toIntOrNull() ?: return false,
            fromIntToAnimeStatus(newStatus.status.internalId),
            newStatus.score,
            newStatus.watchedEpisodes
        )
    }

    companion object {
        const val MAX_STALE = 60 * 10
        private val aniListStatusString =
            arrayOf("CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING")

        // Format mapping: UI text → AniList MediaFormat enum
        private val formatMapping = mapOf(
            "TV Show" to "TV",
            "Movie" to "MOVIE",
            "TV Short" to "TV_SHORT",
            "Special" to "SPECIAL",
            "OVA" to "OVA",
            "ONA" to "ONA",
            "Music" to "MUSIC"
        )

        fun mapFormatToAniListEnum(format: String?): String? {
            return format?.let { formatMapping[it] }
        }

        const val ANILIST_CACHED_LIST: String = "anilist_cached_list"

        private fun fixName(name: String): String {
            return name.lowercase(Locale.ROOT).replace(" ", "")
                .replace("[^a-zA-Z0-9]".toRegex(), "")
        }

        private suspend fun searchShows(name: String): GetSearchRoot? {
            try {
                val query = """
                query (${"$"}id: Int, ${"$"}page: Int, ${"$"}search: String, ${"$"}type: MediaType) {
                    Page (page: ${"$"}page, perPage: 10) {
                        media (id: ${"$"}id, search: ${"$"}search, type: ${"$"}type) {
                            id
                            idMal
                            seasonYear
                            startDate { year month day }
                            title {
                                romaji
                            }
                            averageScore
                            meanScore
                            nextAiringEpisode {
                                timeUntilAiring
                                episode
                            }
                            trailer { id site thumbnail }
                            bannerImage
                            recommendations {
                                nodes {
                                    id
                                    mediaRecommendation {
                                        id
                                        title {
                                            english
                                            romaji
                                        }
                                        idMal
                                        coverImage { medium large extraLarge }
                                        averageScore
                                    }
                                }
                            }
                            relations {
                                edges {
                                    id
                                    relationType(version: 2)
                                    node {
                                        format
                                        id
                                        idMal
                                        coverImage { medium large extraLarge }
                                        averageScore
                                        title {
                                            english
                                            romaji
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                """
                val data =
                    mapOf(
                        "query" to query,
                        "variables" to
                                mapOf(
                                    "search" to name,
                                    "page" to 1,
                                    "type" to "ANIME"
                                ).toJson()
                    )

                val res = app.post(
                    "https://graphql.anilist.co/",
                    //headers = mapOf(),
                    data = data,//(if (vars == null) mapOf("query" to q) else mapOf("query" to q, "variables" to vars))
                    timeout = 5000 // REASONABLE TIMEOUT
                ).text.replace("\\", "")
                return res.toKotlinObject()
            } catch (e: Exception) {
                logError(e)
            }
            return null
        }

        // Should use https://gist.github.com/purplepinapples/5dc60f15f2837bf1cea71b089cfeaa0a
        suspend fun getShowId(malId: String?, name: String, year: Int?): GetSearchMedia? {
            // Strips these from the name
            val blackList = listOf(
                "TV Dubbed",
                "(Dub)",
                "Subbed",
                "(TV)",
                "(Uncensored)",
                "(Censored)",
                "(\\d+)" // year
            )
            val blackListRegex =
                Regex(
                    """ (${
                        blackList.joinToString(separator = "|").replace("(", "\\(")
                            .replace(")", "\\)")
                    })"""
                )
            //println("NAME $name NEW NAME ${name.replace(blackListRegex, "")}")
            val shows = searchShows(name.replace(blackListRegex, ""))

            shows?.data?.page?.media?.find {
                (malId ?: "NONE") == it.idMal.toString()
            }?.let { return it }

            val filtered =
                shows?.data?.page?.media?.filter {
                    (((it.startDate.year ?: year.toString()) == year.toString()
                            || year == null))
                }
            filtered?.forEach {
                it.title.romaji?.let { romaji ->
                    if (fixName(romaji) == fixName(name)) return it
                }
            }

            return filtered?.firstOrNull()
        }

        // Changing names of these will show up in UI
        enum class AniListStatusType(var value: Int, @StringRes val stringRes: Int) {
            Watching(0, R.string.type_watching),
            Completed(1, R.string.type_completed),
            Paused(2, R.string.type_on_hold),
            Dropped(3, R.string.type_dropped),
            Planning(4, R.string.type_plan_to_watch),
            ReWatching(5, R.string.type_re_watching),
            None(-1, R.string.none)
        }

        fun fromIntToAnimeStatus(inp: Int): AniListStatusType {//= AniListStatusType.values().first { it.value == inp }
            return when (inp) {
                -1 -> AniListStatusType.None
                0 -> AniListStatusType.Watching
                1 -> AniListStatusType.Completed
                2 -> AniListStatusType.Paused
                3 -> AniListStatusType.Dropped
                4 -> AniListStatusType.Planning
                5 -> AniListStatusType.ReWatching
                else -> AniListStatusType.None
            }
        }

        fun convertAniListStringToStatus(string: String): AniListStatusType {
            return fromIntToAnimeStatus(aniListStatusString.indexOf(string))
        }

        private suspend fun getSeason(id: Int): SeasonResponse {
            val q = """
               query (${'$'}id: Int = $id) {
                   Media (id: ${'$'}id, type: ANIME) {
                       id
                       idMal
                       coverImage {
                           extraLarge
                           large
                           medium
                           color
                       }
                       title {
                           romaji
                           english
                           native
                           userPreferred
                       }
                       duration
                       episodes
                       genres
                       synonyms
                       averageScore
                       isAdult
                       description(asHtml: false)
                       characters(sort: ROLE page: 1 perPage: 20) {
                           edges {
                               role
                               voiceActors {
                                   name {
                                       userPreferred
                                       full
                                       native
                                   }
                                   age
                                   image {
                                       large
                                       medium
                                   }
                               }
                               node {
                                   name {
                                       userPreferred
                                       full
                                       native
                                   }
                                   age
                                   image {
                                       large
                                       medium
                                   }
                               }
                           }
                       }
                       trailer {
                           id
                           site
                           thumbnail
                       }
                       relations {
                           edges {
                                id
                                relationType(version: 2)
                                node {
                                     id
                                     coverImage {
                                         extraLarge
                                         large
                                         medium
                                         color
                                     }
                                }
                           }
                       }
                       recommendations {
                           edges {
                               node {
                                   mediaRecommendation {
                                       id
                                       coverImage {
                                           extraLarge
                                           large
                                           medium
                                           color
                                       }
                                       title {
                                           romaji
                                           english
                                           native
                                           userPreferred
                                       }
                                   }
                               }
                           }
                       }
                       nextAiringEpisode {
                           timeUntilAiring
                           episode
                       }
                       format
                   }
               }
        """
            val data = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to q),
                cacheTime = 0,
            ).text

            return tryParseJson(data) ?: throw ErrorLoadingException("Error parsing $data")
        }
    }

    private suspend fun getDataAboutId(auth : AuthData, id: Int): AniListTitleHolder? {
        val q =
            """query (${'$'}id: Int = $id) { # Define which variables will be used in the query (id)
                Media (id: ${'$'}id, type: ANIME) { # Insert our variables into the query arguments (id) (type: ANIME is hard-coded in the query)
                    id
                    episodes
                    isFavourite
                    mediaListEntry {
                        progress
                        status
                        score (format: POINT_100)
                    }
                    title {
                        english
                        romaji
                    }
                }
            }"""

        val data = postApi(auth.token, q, true)
        val d = parseJson<GetDataRoot>(data ?: return null)

        val main = d.data?.media
        if (main?.mediaListEntry != null) {
            return AniListTitleHolder(
                title = main.title,
                id = id,
                isFavourite = main.isFavourite,
                progress = main.mediaListEntry.progress,
                episodes = main.episodes,
                score = main.mediaListEntry.score,
                type = fromIntToAnimeStatus(aniListStatusString.indexOf(main.mediaListEntry.status)),
            )
        } else {
            return AniListTitleHolder(
                title = main?.title,
                id = id,
                isFavourite = main?.isFavourite,
                progress = 0,
                episodes = main?.episodes,
                score = 0,
                type = AniListStatusType.None,
            )
        }

    }

    private suspend fun postApi(token : AuthToken, q: String, cache: Boolean = false): String? {
        return app.post(
            "https://graphql.anilist.co/",
            headers = mapOf(
                "Authorization" to "Bearer ${token.accessToken ?: return null}",
                if (cache) "Cache-Control" to "max-stale=$MAX_STALE" else "Cache-Control" to "no-cache"
            ),
            cacheTime = 0,
            data = mapOf(
                "query" to URLEncoder.encode(
                    q,
                    "UTF-8"
                )
            ), //(if (vars == null) mapOf("query" to q) else mapOf("query" to q, "variables" to vars))
            timeout = 5 // REASONABLE TIMEOUT
        ).text.replace("\\/", "/")
    }


    data class MediaRecommendation(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: Title?,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("coverImage") val coverImage: CoverImage?,
        @JsonProperty("averageScore") val averageScore: Int?
    )

    data class FullAnilistList(
        @JsonProperty("data") val data: Data?
    )

    data class CompletedAt(
        @JsonProperty("year") val year: Int,
        @JsonProperty("month") val month: Int,
        @JsonProperty("day") val day: Int
    )

    data class StartedAt(
        @JsonProperty("year") val year: String?,
        @JsonProperty("month") val month: String?,
        @JsonProperty("day") val day: String?
    )

    data class Title(
        @JsonProperty("english") val english: String?,
        @JsonProperty("romaji") val romaji: String?
    )

    data class CoverImage(
        @JsonProperty("medium") val medium: String?,
        @JsonProperty("large") val large: String?,
        @JsonProperty("extraLarge") val extraLarge: String?
    )

    data class Media(
        @JsonProperty("id") val id: Int,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("season") val season: String?,
        @JsonProperty("seasonYear") val seasonYear: Int,
        @JsonProperty("format") val format: String?,
        //@JsonProperty("source") val source: String,
        @JsonProperty("episodes") val episodes: Int,
        @JsonProperty("title") val title: Title,
        @JsonProperty("description") val description: String?,
        @JsonProperty("coverImage") val coverImage: CoverImage,
        @JsonProperty("synonyms") val synonyms: List<String>,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
    )

    data class Entries(
        @JsonProperty("status") val status: String?,
        @JsonProperty("completedAt") val completedAt: CompletedAt,
        @JsonProperty("startedAt") val startedAt: StartedAt,
        @JsonProperty("updatedAt") val updatedAt: Int,
        @JsonProperty("progress") val progress: Int,
        @JsonProperty("score") val score: Int,
        @JsonProperty("private") val private: Boolean,
        @JsonProperty("media") val media: Media
    ) {
        fun toLibraryItem(): SyncAPI.LibraryItem {
            return SyncAPI.LibraryItem(
                // English title first
                this.media.title.english ?: this.media.title.romaji
                ?: this.media.synonyms.firstOrNull()
                ?: "",
                "https://anilist.co/anime/${this.media.id}/",
                this.media.id.toString(),
                this.progress,
                this.media.episodes,
                Score.from100(this.score),
                this.updatedAt.toLong(),
                "AniList",
                TvType.Anime,
                this.media.coverImage.extraLarge ?: this.media.coverImage.large
                ?: this.media.coverImage.medium,
                null,
                null,
                this.media.seasonYear.toYear(),
                null,
                plot = this.media.description,
            )
        }
    }

    data class Lists(
        @JsonProperty("status") val status: String?,
        @JsonProperty("entries") val entries: List<Entries>
    )

    data class MediaListCollection(
        @JsonProperty("lists") val lists: List<Lists>
    )

    data class Data(
        @JsonProperty("MediaListCollection") val mediaListCollection: MediaListCollection
    )

    private suspend fun getAniListAnimeListSmart(auth: AuthData): Array<Lists>? {
        return if (requireLibraryRefresh) {
            val list = getFullAniListList(auth)?.data?.mediaListCollection?.lists?.toTypedArray()
            if (list != null) {
                setKey(ANILIST_CACHED_LIST, auth.user.id.toString(), list)
            }
            list
        } else {
            getKey<Array<Lists>>(
                ANILIST_CACHED_LIST,
                auth.user.id.toString()
            ) as? Array<Lists>
        }
    }

    override suspend fun library(auth : AuthData?): SyncAPI.LibraryMetadata? {
        val list = getAniListAnimeListSmart(auth ?: return null)?.groupBy {
            convertAniListStringToStatus(it.status ?: "").stringRes
        }?.mapValues { group ->
            group.value.map { it.entries.map { entry -> entry.toLibraryItem() } }.flatten()
        } ?: emptyMap()

        // To fill empty lists when AniList does not return them
        val baseMap =
            AniListStatusType.entries.filter { it.value >= 0 }.associate {
                it.stringRes to emptyList<SyncAPI.LibraryItem>()
            }

        return SyncAPI.LibraryMetadata(
            (baseMap + list).map { SyncAPI.LibraryList(txt(it.key), it.value) },
            setOf(
                ListSorting.AlphabeticalA,
                ListSorting.AlphabeticalZ,
                ListSorting.UpdatedNew,
                ListSorting.UpdatedOld,
                ListSorting.ReleaseDateNew,
                ListSorting.ReleaseDateOld,
                ListSorting.RatingHigh,
                ListSorting.RatingLow,
            )
        )
    }

    private suspend fun getFullAniListList(auth : AuthData): FullAnilistList? {
        val userID = auth.user.id
        val mediaType = "ANIME"

        val query = """
                query (${'$'}userID: Int = $userID, ${'$'}MEDIA: MediaType = $mediaType) {
                    MediaListCollection (userId: ${'$'}userID, type: ${'$'}MEDIA) { 
                        lists {
                            status
                            entries
                            {
                                status
                                completedAt { year month day }
                                startedAt { year month day }
                                updatedAt
                                progress
                                score (format: POINT_100)
                                private
                                media
                                {
                                    id
                                    idMal
                                    season
                                    seasonYear
                                    format
                                    episodes
                                    chapters
                                    title
                                    {
                                        english
                                        romaji
                                    }
                                    coverImage { extraLarge large medium }
                                    synonyms
                                    nextAiringEpisode {
                                        timeUntilAiring
                                        episode
                                    }
                                }
                            }
                        }
                    }
                    }
            """
        val text = postApi(auth.token, query)
        return text?.toKotlinObject()
    }

    suspend fun toggleLike(auth : AuthData, id: Int): Boolean {
        val q = """mutation (${'$'}animeId: Int = $id) {
				ToggleFavourite (animeId: ${'$'}animeId) {
					anime {
						nodes {
							id
							title {
								romaji
							}
						}
					}
				}
			}"""
        val data = postApi(auth.token, q)
        return data != ""
    }

    /** Used to query a saved MediaItem on the list to get the id for removal */
    data class MediaListItemRoot(@JsonProperty("data") val data: MediaListItem? = null)
    data class MediaListItem(@JsonProperty("MediaList") val mediaList: MediaListId? = null)
    data class MediaListId(@JsonProperty("id") val id: Long? = null)

    private suspend fun postDataAboutId(
        auth : AuthData,
        id: Int,
        type: AniListStatusType,
        score: Score?,
        progress: Int?
    ): Boolean {
        val userID = auth.user.id

        val q =
            // Delete item if status type is None
            if (type == AniListStatusType.None) {
                // Get list ID for deletion
                val idQuery = """
                  query MediaList(${'$'}userId: Int = $userID, ${'$'}mediaId: Int = $id) {
                    MediaList(userId: ${'$'}userId, mediaId: ${'$'}mediaId) {
                      id
                    }
                  }
                """
                val response = postApi(auth.token, idQuery)
                val listId =
                    tryParseJson<MediaListItemRoot>(response)?.data?.mediaList?.id ?: return false
                """
                    mutation(${'$'}id: Int = $listId) {
                        DeleteMediaListEntry(id: ${'$'}id) {
                            deleted
                        }
                    }
                """
            } else {
                """mutation (${'$'}id: Int = $id, ${'$'}status: MediaListStatus = ${
                    aniListStatusString[maxOf(
                        0,
                        type.value
                    )]
                }, ${if (score != null) "${'$'}scoreRaw: Int = ${score.toInt(100)}" else ""} , ${if (progress != null) "${'$'}progress: Int = $progress" else ""}) {
                    SaveMediaListEntry (mediaId: ${'$'}id, status: ${'$'}status, scoreRaw: ${'$'}scoreRaw, progress: ${'$'}progress) {
                        id
                        status
                        progress
                        score
                    }
                }"""
            }

        val data = postApi(auth.token, q)
        return data != ""
    }

    private suspend fun getUser(token : AuthToken): AniListUser? {
        val q = """
				{
  					Viewer {
    					id
    					name
						avatar {
							large
						}
                        favourites {
                            anime {
                                nodes {
                                    id
                                }
                            }
                        }
  					}
				}"""
        val data = postApi(token, q)
        if (data.isNullOrBlank()) return null
        val userData = parseJson<AniListRoot>(data)
        val u = userData.data?.viewer ?: return null
        val user = AniListUser(
            u.id,
            u.name,
            u.avatar?.large,
        )
        return user
    }

    suspend fun getAllSeasons(id: Int): List<SeasonResponse?> {
        val seasons = mutableListOf<SeasonResponse?>()
        suspend fun getSeasonRecursive(id: Int) {
            val season = getSeason(id)
            seasons.add(season)
            if (season.data.media.format?.startsWith("TV") == true) {
                season.data.media.relations?.edges?.forEach {
                    if (it.node?.format != null) {
                        if (it.relationType == "SEQUEL" && it.node.format.startsWith("TV")) {
                            getSeasonRecursive(it.node.id)
                            return@forEach
                        }
                    }
                }
            }
        }
        getSeasonRecursive(id)
        return seasons.toList()
    }

    data class SeasonResponse(
        @JsonProperty("data") val data: SeasonData,
    )

    data class SeasonData(
        @JsonProperty("Media") val media: SeasonMedia,
    )

    data class SeasonMedia(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: MediaTitle?,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("format") val format: String?,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
        @JsonProperty("relations") val relations: SeasonEdges?,
        @JsonProperty("coverImage") val coverImage: MediaCoverImage?,
        @JsonProperty("duration") val duration: Int?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("genres") val genres: List<String>?,
        @JsonProperty("synonyms") val synonyms: List<String>?,
        @JsonProperty("averageScore") val averageScore: Int?,
        @JsonProperty("isAdult") val isAdult: Boolean?,
        @JsonProperty("trailer") val trailer: MediaTrailer?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("characters") val characters: CharacterConnection?,
        @JsonProperty("recommendations") val recommendations: RecommendationConnection?,
    )

    data class RecommendationConnection(
        @JsonProperty("edges") val edges: List<RecommendationEdge> = emptyList(),
        @JsonProperty("nodes") val nodes: List<Recommendation> = emptyList(),
        //@JsonProperty("pageInfo") val pageInfo: PageInfo,
    )

    data class RecommendationEdge(
        //@JsonProperty("rating") val rating: Int,
        @JsonProperty("node") val node: Recommendation,
    )

    data class Recommendation(
        val id: Long,
        @JsonProperty("mediaRecommendation") val mediaRecommendation: SeasonMedia?,
    )

    data class CharacterName(
        @JsonProperty("name") val first: String?,
        @JsonProperty("middle") val middle: String?,
        @JsonProperty("last") val last: String?,
        @JsonProperty("full") val full: String?,
        @JsonProperty("native") val native: String?,
        @JsonProperty("alternative") val alternative: List<String>?,
        @JsonProperty("alternativeSpoiler") val alternativeSpoiler: List<String>?,
        @JsonProperty("userPreferred") val userPreferred: String?,
    )

    data class CharacterImage(
        @JsonProperty("large") val large: String?,
        @JsonProperty("medium") val medium: String?,
    )

    data class Character(
        @JsonProperty("name") val name: CharacterName?,
        @JsonProperty("age") val age: String?,
        @JsonProperty("image") val image: CharacterImage?,
    )

    data class CharacterEdge(
        @JsonProperty("id") val id: Int?,
        /**
        MAIN
        A primary character role in the media

        SUPPORTING
        A supporting character role in the media

        BACKGROUND
        A background character in the media
         */
        @JsonProperty("role") val role: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("voiceActors") val voiceActors: List<Staff>?,
        @JsonProperty("favouriteOrder") val favouriteOrder: Int?,
        @JsonProperty("media") val media: List<SeasonMedia>?,
        @JsonProperty("node") val node: Character?,
    )

    data class StaffImage(
        @JsonProperty("large") val large: String?,
        @JsonProperty("medium") val medium: String?,
    )

    data class StaffName(
        @JsonProperty("name") val first: String?,
        @JsonProperty("middle") val middle: String?,
        @JsonProperty("last") val last: String?,
        @JsonProperty("full") val full: String?,
        @JsonProperty("native") val native: String?,
        @JsonProperty("alternative") val alternative: List<String>?,
        @JsonProperty("userPreferred") val userPreferred: String?,
    )

    data class Staff(
        @JsonProperty("image") val image: StaffImage?,
        @JsonProperty("name") val name: StaffName?,
        @JsonProperty("age") val age: Int?,
    )

    data class CharacterConnection(
        @JsonProperty("edges") val edges: List<CharacterEdge>?,
        @JsonProperty("nodes") val nodes: List<Character>?,
        //@JsonProperty("pageInfo")  pageInfo: PageInfo
    )

    data class MediaTrailer(
        @JsonProperty("id") val id: String?,
        @JsonProperty("site") val site: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
    )

    data class MediaCoverImage(
        @JsonProperty("extraLarge") val extraLarge: String?,
        @JsonProperty("large") val large: String?,
        @JsonProperty("medium") val medium: String?,
        @JsonProperty("color") val color: String?,
    )

    data class SeasonNextAiringEpisode(
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("timeUntilAiring") val timeUntilAiring: Int?,
    )

    data class SeasonEdges(
        @JsonProperty("edges") val edges: List<SeasonEdge>?,
    )

    data class SeasonEdge(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("relationType") val relationType: String?,
        @JsonProperty("node") val node: SeasonNode?,
    )

    data class AniListFavoritesMediaConnection(
        @JsonProperty("nodes") val nodes: List<LikeNode>,
    )

    data class AniListFavourites(
        @JsonProperty("anime") val anime: AniListFavoritesMediaConnection,
    )

    data class MediaTitle(
        @JsonProperty("romaji") val romaji: String?,
        @JsonProperty("english") val english: String?,
        @JsonProperty("native") val native: String?,
        @JsonProperty("userPreferred") val userPreferred: String?,
    )

    data class SeasonNode(
        @JsonProperty("id") val id: Int,
        @JsonProperty("format") val format: String?,
        @JsonProperty("title") val title: Title?,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("coverImage") val coverImage: CoverImage?,
        @JsonProperty("averageScore") val averageScore: Int?
//        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
    )

    data class AniListAvatar(
        @JsonProperty("large") val large: String?,
    )

    data class AniListViewer(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("avatar") val avatar: AniListAvatar?,
        @JsonProperty("favourites") val favourites: AniListFavourites?,
    )

    data class AniListData(
        @JsonProperty("Viewer") val viewer: AniListViewer?,
    )

    data class AniListRoot(
        @JsonProperty("data") val data: AniListData?,
    )

    data class AniListUser(
        @JsonProperty("id") val id: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("picture") val picture: String?,
    )

    data class LikeNode(
        @JsonProperty("id") val id: Int?,
        //@JsonProperty("idMal") public int idMal;
    )

    data class LikePageInfo(
        @JsonProperty("total") val total: Int?,
        @JsonProperty("currentPage") val currentPage: Int?,
        @JsonProperty("lastPage") val lastPage: Int?,
        @JsonProperty("perPage") val perPage: Int?,
        @JsonProperty("hasNextPage") val hasNextPage: Boolean?,
    )

    data class LikeAnime(
        @JsonProperty("nodes") val nodes: List<LikeNode>?,
        @JsonProperty("pageInfo") val pageInfo: LikePageInfo?,
    )

    data class LikeFavourites(
        @JsonProperty("anime") val anime: LikeAnime?,
    )

    data class LikeViewer(
        @JsonProperty("favourites") val favourites: LikeFavourites?,
    )

    data class LikeData(
        @JsonProperty("Viewer") val viewer: LikeViewer?,
    )

    data class LikeRoot(
        @JsonProperty("data") val data: LikeData?,
    )

    data class AniListTitleHolder(
        @JsonProperty("title") val title: Title?,
        @JsonProperty("isFavourite") val isFavourite: Boolean?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("progress") val progress: Int?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("score") val score: Int?,
        @JsonProperty("type") val type: AniListStatusType?,
    )

    data class GetDataMediaListEntry(
        @JsonProperty("progress") val progress: Int?,
        @JsonProperty("status") val status: String?,
        @JsonProperty("score") val score: Int?,
    )

    data class Nodes(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("mediaRecommendation") val mediaRecommendation: MediaRecommendation?
    )

    data class GetDataMedia(
        @JsonProperty("isFavourite") val isFavourite: Boolean?,
        @JsonProperty("episodes") val episodes: Int?,
        @JsonProperty("title") val title: Title?,
        @JsonProperty("mediaListEntry") val mediaListEntry: GetDataMediaListEntry?
    )

    data class Recommendations(
        @JsonProperty("nodes") val nodes: List<Nodes>?
    )

    data class GetDataData(
        @JsonProperty("Media") val media: GetDataMedia?,
    )

    data class GetDataRoot(
        @JsonProperty("data") val data: GetDataData?,
    )

    data class GetSearchTitle(
        @JsonProperty("romaji") val romaji: String?,
    )

    data class TrailerObject(
        @JsonProperty("id") val id: String?,
        @JsonProperty("thumbnail") val thumbnail: String?,
        @JsonProperty("site") val site: String?,
    )

    data class GetSearchMedia(
        @JsonProperty("id") val id: Int,
        @JsonProperty("idMal") val idMal: Int?,
        @JsonProperty("seasonYear") val seasonYear: Int,
        @JsonProperty("title") val title: GetSearchTitle,
        @JsonProperty("startDate") val startDate: StartedAt,
        @JsonProperty("averageScore") val averageScore: Int?,
        @JsonProperty("meanScore") val meanScore: Int?,
        @JsonProperty("bannerImage") val bannerImage: String?,
        @JsonProperty("trailer") val trailer: TrailerObject?,
        @JsonProperty("nextAiringEpisode") val nextAiringEpisode: SeasonNextAiringEpisode?,
        @JsonProperty("recommendations") val recommendations: Recommendations?,
        @JsonProperty("relations") val relations: SeasonEdges?
    )

    data class GetSearchPage(
        @JsonProperty("Page") val page: GetSearchData?,
    )

    data class GetSearchData(
        @JsonProperty("media") val media: List<GetSearchMedia>?,
    )

    data class GetSearchRoot(
        @JsonProperty("data") val data: GetSearchPage?,
    )

    // Genre Browse Feature
    suspend fun getGenreCollection(): List<String>? {
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection called")
        val query = """
            query {
                GenreCollection
            }
        """
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: Query = $query")
        val data = app.post(
            "https://graphql.anilist.co/",
            data = mapOf("query" to query),
            timeout = 5000
        ).text
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: Raw response = $data")
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: Attempting to parse JSON")
        val response = tryParseJson<GenreCollectionResponse>(data)
        if (response == null) {
            android.util.Log.e("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: FAILED to parse JSON response")
            android.util.Log.e("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: Response data = $data")
            return null
        }
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: Successfully parsed JSON")
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getGenreCollection: genreCollection = ${response.data?.genreCollection}")
        return response.data?.genreCollection
    }

    suspend fun getMediaTagCollection(): List<MediaTag>? {
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection called")
        val query = """
            query {
                MediaTagCollection {
                    name
                    description
                    category
                    isAdult
                }
            }
        """
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: Query = $query")
        val data = app.post(
            "https://graphql.anilist.co/",
            data = mapOf("query" to query),
            timeout = 5000
        ).text
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: Raw response = $data")
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: Attempting to parse JSON")
        val response = tryParseJson<MediaTagCollectionResponse>(data)
        if (response == null) {
            android.util.Log.e("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: FAILED to parse JSON response")
            android.util.Log.e("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: Response data = $data")
            return null
        }
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: Successfully parsed JSON")
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaTagCollection: mediaTagCollection size = ${response.data?.mediaTagCollection?.size}")
        return response.data?.mediaTagCollection
    }

    suspend fun getMediaByGenre(
        genres: List<String>,
        tags: List<String> = emptyList(),
        excludedGenres: List<String> = emptyList(),
        excludedTags: List<String> = emptyList(),
        page: Int = 1,
        seasonYear: Int? = null,
        season: String? = null,
        format: String? = null,
        sort: String? = null,
        search: String? = null,
        isAdult: Boolean? = null
    ): MediaByGenreResponse? {
        android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: getMediaByGenre called with genres=$genres, excludedGenres=$excludedGenres, tags=$tags, excludedTags=$excludedTags")
        
        // OPTIMIZATION: Cache genre collection to avoid redundant API calls
        android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Checking if we need to fetch genre collection")
        val officialGenres = getGenreCollection()
        android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Genre collection fetched, size=${officialGenres?.size}")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: official genres from API=$officialGenres")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: requested genres=$genres")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: search=$search")
        
        // OPTIMIZATION: Only normalize if we have genres to normalize and official genres are available
        val normalizedGenres = if (genres.isNotEmpty() && officialGenres != null) {
            android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Normalizing ${genres.size} genres")
            genres.map { genre ->
                val normalized = officialGenres.firstOrNull { it.equals(genre, ignoreCase = true) } ?: genre
                android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Genre normalization: '$genre' -> '$normalized'")
                normalized
            }
        } else {
            android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: No genres to normalize or official genres unavailable, using original genres")
            genres
        }
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: normalized genres=$normalizedGenres")
        
        // OPTIMIZATION: Only normalize excluded genres if we have them and official genres are available
        val normalizedExcludedGenres = if (excludedGenres.isNotEmpty() && officialGenres != null) {
            android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Normalizing ${excludedGenres.size} excluded genres")
            excludedGenres.map { genre ->
                val normalized = officialGenres.firstOrNull { it.equals(genre, ignoreCase = true) } ?: genre
                android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Excluded genre normalization: '$genre' -> '$normalized'")
                normalized
            }
        } else {
            android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: No excluded genres to normalize or official genres unavailable, using original excluded genres")
            excludedGenres
        }
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: excluded genres=$excludedGenres")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: normalized excluded genres=$normalizedExcludedGenres")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: excluded tags=$excludedTags")
        
        android.util.Log.d("API_OPTIMIZATION", "API_OPTIMIZATION: Genre normalization completed - included: ${normalizedGenres.size}, excluded: ${normalizedExcludedGenres.size}")
        
        // Single master query with all possible filter variables including search and exclusions
        val query = """
            query (${'$'}page: Int, ${'$'}perPage: Int, ${'$'}search: String, ${'$'}genres: [String], ${'$'}tags: [String], ${'$'}genre_not_in: [String], ${'$'}tag_not_in: [String], ${'$'}seasonYear: Int, ${'$'}season: MediaSeason, ${'$'}format: MediaFormat, ${'$'}sort: [MediaSort], ${'$'}isAdult: Boolean) {
                Page(page: ${'$'}page, perPage: ${'$'}perPage) {
                    pageInfo { hasNextPage }
                    media(type: ANIME, search: ${'$'}search, genre_in: ${'$'}genres, tag_in: ${'$'}tags, genre_not_in: ${'$'}genre_not_in, tag_not_in: ${'$'}tag_not_in, seasonYear: ${'$'}seasonYear, season: ${'$'}season, format: ${'$'}format, sort: ${'$'}sort, isAdult: ${'$'}isAdult) {
                        id
                        title { romaji english }
                        coverImage { large }
                    }
                }
            }
        """
        
        // Build variables map, omitting null/empty values
        val variables = mutableMapOf<String, Any?>(
            "page" to page,
            "perPage" to 25
        )
        if (search != null) variables["search"] = search
        if (normalizedGenres.isNotEmpty()) variables["genres"] = normalizedGenres
        if (tags.isNotEmpty()) variables["tags"] = tags
        if (normalizedExcludedGenres.isNotEmpty()) variables["genre_not_in"] = normalizedExcludedGenres
        if (excludedTags.isNotEmpty()) variables["tag_not_in"] = excludedTags
        if (seasonYear != null) variables["seasonYear"] = seasonYear
        if (season != null) variables["season"] = season
        if (format != null) variables["format"] = format
        if (sort != null) variables["sort"] = listOf(sort)
        if (isAdult != null) variables["isAdult"] = isAdult
        
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: variables=$variables")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: variablesJson=${variables.toJson()}")
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: query=$query")
        
        val data = app.post(
            "https://graphql.anilist.co/",
            data = mapOf(
                "query" to query,
                "variables" to variables.toJson()
            ),
            timeout = 5000
        ).text
        
        android.util.Log.d("GenreFilter", "AniListApi.getMediaByGenre: raw response=$data")
        
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaByGenre: Attempting to parse JSON response")
        val response = tryParseJson<MediaByGenreResponse>(data)
        if (response == null) {
            android.util.Log.e("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaByGenre: FAILED to parse JSON response")
            android.util.Log.e("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaByGenre: Response data = $data")
            return null
        }
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaByGenre: Successfully parsed JSON")
        android.util.Log.d("API_PARSE_LOGGING", "API_PARSE_LOGGING: getMediaByGenre: media items count = ${response.data?.page?.media?.size}")
        return response
    }

    data class GenreCollectionResponse(
        @JsonProperty("data") val data: GenreCollectionData?
    )

    data class GenreCollectionData(
        @JsonProperty("GenreCollection") val genreCollection: List<String>?
    )

    data class MediaTagCollectionResponse(
        @JsonProperty("data") val data: MediaTagCollectionData?
    )

    data class MediaTagCollectionData(
        @JsonProperty("MediaTagCollection") val mediaTagCollection: List<MediaTag>?
    )

    data class MediaTag(
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("category") val category: String?,
        @JsonProperty("isAdult") val isAdult: Boolean?
    )

    data class MediaByGenreResponse(
        @JsonProperty("data") val data: MediaByGenreData?
    )

    data class MediaByGenreData(
        @JsonProperty("Page") val page: MediaByGenrePage?
    )

    data class MediaByGenrePage(
        @JsonProperty("pageInfo") val pageInfo: MediaByGenrePageInfo?,
        @JsonProperty("media") val media: List<MediaByGenreItem>?
    )

    data class MediaByGenrePageInfo(
        @JsonProperty("hasNextPage") val hasNextPage: Boolean?
    )

    data class MediaByGenreItem(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: MediaTitle?,
        @JsonProperty("coverImage") val coverImage: CoverImage?
    )
}