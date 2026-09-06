package com.dreamdisplayx.platform.server.managers

import com.dreamdisplayx.api.playback.model.DisplayAccess
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.api.playback.model.PlaybackMode
import com.dreamdisplayx.api.playback.model.PlaylistEndBehavior
import com.dreamdisplayx.api.playback.model.PlaylistEnqueuePolicy
import com.dreamdisplayx.api.playback.model.PlaylistItemRecord
import com.dreamdisplayx.api.playback.model.DisplayPlaylist
import com.dreamdisplayx.api.security.policy.MediaUrlPolicy
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplayx.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplayx.platform.server.storage.StorageBackend
import com.dreamdisplayx.util.natives.SqliteAndroidCompat
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.DIRECTION_TO_ORDINAL
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.ORDINAL_TO_DIRECTION
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.packFacing
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.packInts
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.packPos
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.toBytes
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.toUUID
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.unpackFacingOrdinal
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.unpackInts
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.unpackPos
import com.dreamdisplayx.platform.server.utils.StoragePackingUtil.unpackRotation
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.replace
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.jspecify.annotations.NullMarked
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import kotlin.time.Instant

/**
 * Exposed table definition for persistent display rows. Positions and dimensions are packed to keep
 * the schema compact across both `Paper` and `Fabric` server variants.
 */
class DisplaysTable(prefix: String = "") : Table("${prefix}displays") {
    /** Unique identifier for the display. */
    val id = binary("id", 16)

    /** Unique identifier for the owner of the display. */
    val ownerId = binary("ownerId", 16)

    /** Video code or URL associated with the display. */
    val videoCode = varchar("videoCode", MediaUrlPolicy.MAX_URL_LENGTH).default("")

    /** Name of the world where the display is located. */
    val world = varchar("world", 255)

    /** Packed long representing the first corner of the display area. */
    val pos1 = long("pos1")

    /** Packed long representing the opposite corner of the display area. */
    val pos2 = long("pos2")

    /** Packed long representing the width and height of the display. */
    val size = long("size")

    /** Integer representing the facing direction and rotation of the display. */
    val facing = integer("facing")

    /** Boolean indicating whether the display is synchronized across clients. */
    val isSync = bool("isSync")

    /** Nullable long representing the duration of the video associated with the display. */
    val duration = long("duration").nullable()

    /** Playback position in nanos, persisted so a server restart resumes playback instead of restarting. */
    val position = long("position").default(0)

    /** String representing the language code of the video associated with the display. */
    val lang = varchar("lang", 255).default("")

    /**
     * Legacy locked flag, still written so a downgrade keeps working. Superseded by [access], which
     * distinguishes the region level the boolean could not express.
     */
    val isLocked = bool("isLocked").default(true)

    /**
     * Wire ordinal of the display's [com.dreamdisplays.api.playback.model.DisplayAccess]. Nullable so
     * rows written before this column existed are recognisable and fall back to [isLocked] on load,
     * instead of every one of them reading as the column default.
     */
    val access = integer("access").nullable()

    /** Integer representing the playback mode of the display. */
    val mode = integer("mode").default(PlaybackMode.LOCAL.wire)

    /** Optional, space-free alias usable anywhere a display id is accepted; unique across displays. */
    val name = varchar("name", 32).nullable()

    /** Epoch millis of a pending scheduled-playback start, or null when no schedule is set. */
    val scheduledStart = long("scheduledStart").nullable()

    /** Wire ordinal of the [com.dreamdisplayx.api.playback.model.PlaybackAction] [scheduledStart] will apply, or null. */
    val scheduledAction = integer("scheduledAction").nullable()

    /** Primary key for the displays table, which is the unique identifier of the display. */
    override val primaryKey = PrimaryKey(id)
}

/**
 * One row per display playlist: the playing index and the two wire policies. The items themselves
 * live in [PlaylistItemsTable]; a playlist row is created lazily on first item / settings write.
 */
class PlaylistsTable(prefix: String = "") : Table("${prefix}playlists") {
    /** Owning display id (16-byte binary, matching the displays table). */
    val displayId = binary("displayId", 16)

    /** Index into the item list currently playing, or -1 when nothing is playing. */
    val currentIndex = integer("currentIndex").default(-1)

    /** Wire ordinal of the [PlaylistEndBehavior]. */
    val endBehavior = integer("endBehavior").default(PlaylistEndBehavior.CONTINUE.wire)

    /** Wire ordinal of the [PlaylistEnqueuePolicy]. */
    val enqueuePolicy = integer("enqueuePolicy").default(PlaylistEnqueuePolicy.OWNER_ONLY.wire)

    /** Whether playlist mode is active for this display (auto-advance + pick-to-enqueue). */
    val enabled = bool("enabled").default(true)

    /** Primary key for the playlists table, which is the owning display id. */
    override val primaryKey = PrimaryKey(displayId)
}

/** One queued media item; [queueIndex] is the 0-based queue slot and is rewritten on reorder. */
class PlaylistItemsTable(prefix: String = "") : Table("${prefix}playlist_items") {
    /** Stable item identity used by client remove / move / skip commands. */
    val itemId = binary("itemId", 16)

    /** Owning display id. */
    val displayId = binary("displayId", 16)

    /** 0-based queue slot; contiguous and compacted on every reorder. */
    val queueIndex = integer("queueIndex")

    /** Media URL to play. */
    val url = varchar("url", MediaUrlPolicy.MAX_URL_LENGTH).default("")

    /** Optional audio language for the media. */
    val lang = varchar("lang", 255).default("")

    /** Optional display title resolved by the requester's client. */
    val title = varchar("title", 255).default("")

    /** True while waiting for the display owner's approval. */
    val pending = bool("pending").default(false)

    /** The player who added the item. */
    val requesterId = binary("requesterId", 16)

    /** Primary key for the playlist items table, which is the stable item id. */
    override val primaryKey = PrimaryKey(itemId)
}

/**
 * `SQL`-backed display storage adapter. Loads persisted rows into platform-specific [DisplayData]
 * objects and writes updates from managers back to `SQLite` or `MySQL` through `Exposed` / `Hikari`.
 */
@NullMarked
class StorageManager(
    backend: StorageBackend,
    dataDir: File,
    tablePrefix: String = "",
    host: String = "",
    port: String = "",
    database: String = "",
    username: String = "",
    password: String = "",
    useSSL: Boolean = false,
    jdbcUrl: String = "",
) {
    private val logger: Logger = LoggerFactory.getLogger(javaClass)
    private val table = DisplaysTable(tablePrefix)
    private val playlistTable = PlaylistsTable(tablePrefix)
    private val playlistItemsTable = PlaylistItemsTable(tablePrefix)

    private val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = when {
            // A fully custom JDBC URL wins over the split host/port/database fields.
            jdbcUrl.isNotBlank() -> jdbcUrl
            backend == StorageBackend.SQLITE -> "jdbc:sqlite:${File(dataDir, "dreamdisplayx.db").absolutePath}"
            else -> "jdbc:mysql://$host:$port/$database?autoReconnect=true&useSSL=$useSSL&useInformationSchema=false"
        }
        if (backend != StorageBackend.SQLITE) {
            this.username = username
            this.password = password
        }
        // Read more why SQLite should use a single connection:
        // https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing
        maximumPoolSize = if (backend == StorageBackend.SQLITE) 1 else 3
        isAutoCommit = false
    })

    /** Exposed database connection. */
    private val db = Database.connect(dataSource)

    /**
     * Creates the displays table if missing and applies in-place column migrations (`lang`, `isLocked`, `videoCode`
     * widening). Drop-column statements are filtered out.
     */
    fun createSchema() {
        transaction(db) {
            MigrationUtils.statementsRequiredForDatabaseMigration(table, playlistTable, playlistItemsTable)
                .filterNot { it.contains("DROP COLUMN", ignoreCase = true) }
                .forEach { stmt -> exec(stmt) }
        }
    }

    /** Persists all in-memory displays and closes the database connection on plugin shutdown. */
    fun disconnect() = dataSource.close()

    /** Load all displays from the database, returning a list of [DisplayData] objects. */
    @PaperOnly
    fun loadAllPaperDisplays(): List<PaperDisplayData> = transaction(db) {
        table.selectAll().mapNotNull(::rowToPaper)
    }

    /** Upserts the full row for [data] into the displays table. */
    @PaperOnly
    fun saveDisplay(data: PaperDisplayData) {
        val worldName = data.pos1.world?.name ?: run {
            logger.error("Cannot save display ${data.id}: world is null.")
            return
        }
        upsert(
            data, worldName,
            packPos(data.pos1.blockX, data.pos1.blockY, data.pos1.blockZ),
            packPos(data.pos2.blockX, data.pos2.blockY, data.pos2.blockZ),
            packFacing(data.facing.ordinal, data.rotation)
        )
    }

    /** Deletes the display with the given [data] from the displays table. */
    fun deleteDisplay(data: DisplayData) = delete(data.id)

    /** Converts a row from the displays table into a [PaperDisplayData] object, or null if the row */
    @PaperOnly
    private fun rowToPaper(row: ResultRow): PaperDisplayData? {
        val id = row[table.id].toUUID()
        val worldName = row[table.world]
        val world = Bukkit.getWorld(worldName)
            ?: runCatching { UUID.fromString(worldName) }.getOrNull()?.let { Bukkit.getWorld(it) }
        if (world == null) {
            logger.warn("Skipping display $id: world '$worldName' not found.")
            return null
        }
        val (x1, y1, z1) = unpackPos(row[table.pos1])
        val (x2, y2, z2) = unpackPos(row[table.pos2])
        val (w, h) = unpackInts(row[table.size])
        val facing = BlockFace.entries.getOrNull(unpackFacingOrdinal(row[table.facing])) ?: BlockFace.NORTH
        val rotation = unpackRotation(row[table.facing])

        return PaperDisplayData(
            id, row[table.ownerId].toUUID(),
            Location(world, x1.toDouble(), y1.toDouble(), z1.toDouble()),
            Location(world, x2.toDouble(), y2.toDouble(), z2.toDouble()),
            w, h, facing, rotation,
        ).applyCommon(row)
    }

    /** Load all displays from the database, returning a list of [VanillaDisplayData] objects. */
    fun loadAllVanillaDisplays(): List<VanillaDisplayData> = transaction(db) {
        table.selectAll().mapNotNull(::rowToVanilla)
    }

    /** Upserts the full row for [data] into the displays table. */
    fun saveDisplay(data: VanillaDisplayData) {
        upsert(
            data, data.worldKey,
            packPos(data.pos1.x, data.pos1.y, data.pos1.z),
            packPos(data.pos2.x, data.pos2.y, data.pos2.z),
            packFacing(DIRECTION_TO_ORDINAL.getValue(data.facing), data.rotation)
        )
    }

    /** Converts a row from the displays table into a [VanillaDisplayData] object. */
    private fun rowToVanilla(row: ResultRow): VanillaDisplayData {
        val (x1, y1, z1) = unpackPos(row[table.pos1])
        val (x2, y2, z2) = unpackPos(row[table.pos2])
        val (w, h) = unpackInts(row[table.size])
        val facing = ORDINAL_TO_DIRECTION.getOrDefault(unpackFacingOrdinal(row[table.facing]), Direction.NORTH)
        val rotation = unpackRotation(row[table.facing])

        return VanillaDisplayData(
            row[table.id].toUUID(), row[table.ownerId].toUUID(),
            row[table.world],
            BlockPos(x1, y1, z1), BlockPos(x2, y2, z2),
            w, h, facing, rotation,
        ).applyCommon(row)
    }

    /** Upserts the given display data into the displays table. */
    private fun upsert(data: DisplayData, worldName: String, p1: Long, p2: Long, facingOrd: Int) {
        transaction(db) {
            table.replace {
                it[id] = data.id.toBytes()
                it[ownerId] = data.ownerId.toBytes()
                it[videoCode] = data.url
                it[world] = worldName
                it[pos1] = p1
                it[pos2] = p2
                it[size] = packInts(data.width, data.height)
                it[facing] = facingOrd
                it[isSync] = data.isSync
                it[duration] = data.duration
                it[lang] = data.lang
                it[isLocked] = data.isLocked
                it[access] = data.access.wire
                it[mode] = data.mode.wire
                it[name] = data.name
                it[position] = data.seekPositionNanos
                it[scheduledStart] = data.scheduledStart?.toEpochMilliseconds()
                it[scheduledAction] = data.scheduledAction?.wire
            }
        }
    }

    /** Deletes the display with the given [displayId] from the display table. */
    private fun delete(displayId: UUID) {
        transaction(db) { table.deleteWhere { id eq displayId.toBytes() } }
    }

    /** Loads every persisted playlist (display playlists only; items ordered by queue index). */
    fun loadAllPlaylists(): List<DisplayPlaylist> = transaction(db) {
        val itemsByDisplay = playlistItemsTable.selectAll()
            .map { row ->
                Triple(
                    row[playlistItemsTable.displayId].toUUID(),
                    row[playlistItemsTable.queueIndex],
                    PlaylistItemRecord(
                        itemId = row[playlistItemsTable.itemId].toUUID(),
                        url = row[playlistItemsTable.url],
                        lang = row[playlistItemsTable.lang],
                        title = row[playlistItemsTable.title],
                        pending = row[playlistItemsTable.pending],
                        requesterId = row[playlistItemsTable.requesterId].toUUID(),
                    ),
                )
            }
            .groupBy({ it.first }, { it.second to it.third })
        playlistTable.selectAll().map { row ->
            val displayId = row[playlistTable.displayId].toUUID()
            DisplayPlaylist(
                displayId = displayId,
                items = itemsByDisplay[displayId].orEmpty()
                    .sortedBy { it.first }
                    .map { it.second },
                currentIndex = row[playlistTable.currentIndex],
                endBehavior = PlaylistEndBehavior.fromWire(row[playlistTable.endBehavior]),
                enqueuePolicy = PlaylistEnqueuePolicy.fromWire(row[playlistTable.enqueuePolicy]),
                enabled = row[playlistTable.enabled],
            )
        }
    }

    /** Upserts [playlist] and rewrites its item rows in one transaction. */
    fun savePlaylist(playlist: DisplayPlaylist) {
        transaction(db) {
            playlistTable.replace {
                it[displayId] = playlist.displayId.toBytes()
                it[currentIndex] = playlist.currentIndex
                it[endBehavior] = playlist.endBehavior.wire
                it[enqueuePolicy] = playlist.enqueuePolicy.wire
                it[enabled] = playlist.enabled
            }
            playlistItemsTable.deleteWhere { displayId eq playlist.displayId.toBytes() }
            playlist.items.forEachIndexed { index, item ->
                playlistItemsTable.insert {
                    it[itemId] = item.itemId.toBytes()
                    it[displayId] = playlist.displayId.toBytes()
                    it[queueIndex] = index
                    it[url] = item.url
                    it[lang] = item.lang
                    it[title] = item.title
                    it[pending] = item.pending
                    it[requesterId] = item.requesterId.toBytes()
                }
            }
        }
    }

    /** Removes a display's playlist rows (called when the display itself is deleted). */
    fun deletePlaylist(displayId: UUID) {
        val displayBytes = displayId.toBytes()
        transaction(db) {
            playlistItemsTable.deleteWhere { playlistItemsTable.displayId eq displayBytes }
            playlistTable.deleteWhere { playlistTable.displayId eq displayBytes }
        }
    }

    /** Applies common properties from a row to a display data object, such as URL and playback mode. */
    private fun <T : DisplayData> T.applyCommon(row: ResultRow): T = apply {
        url = row[table.videoCode]
        val stored = PlaybackMode.fromWire(row[table.mode])
        mode = if (stored != PlaybackMode.LOCAL) stored
        else if (row[table.isSync]) PlaybackMode.SYNCED else PlaybackMode.LOCAL
        duration = row[table.duration]
        lang = row[table.lang]
        // Rows predating the access column carry only the old boolean; map it onto a level.
        access = row[table.access]?.let(DisplayAccess::fromWire)
            ?: DisplayAccess.fromLegacyLocked(row[table.isLocked])
        name = row[table.name]
        seekPositionNanos = row[table.position]
        scheduledStart = row[table.scheduledStart]?.let(Instant::fromEpochMilliseconds)
        scheduledAction = row[table.scheduledAction]?.let(PlaybackAction::fromWire)
    }

    companion object {
        init {
            // The SQLite pool is created in the constructor below, so the Android native
            // must be installed before the first instance is built — the companion init
            // runs during class initialization, ahead of any instance property. Desktop
            // platforms short-circuit inside the call (no-op).
            SqliteAndroidCompat.ensure()
        }
    }
}
