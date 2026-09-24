package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.mammonrn.phoneaikiosk.files.NasReader
import com.mammonrn.phoneaikiosk.files.NasSession
import com.mammonrn.phoneaikiosk.files.NasStore
import java.io.IOException

/**
 * Where a track's bytes come from (0.53.0): a file on the phone, or the NAS.
 *
 * A NAS track is the URI "nas://file?p=<path on the share>". [SmbDataSource]
 * opens its own SMB connection for it — one per stream, closed when the
 * stream closes, as the file manager does for every NAS action (DESIGN.md
 * 5ค: a connection kept open between actions stalled on the A07). A seek is
 * a new stream, so a new connection; on the house's network that is ~0.1 s.
 *
 * READ ONLY: NasSession.openRead is GENERIC_READ + FILE_OPEN, and
 * FileManagerTest's read-only check covers it.
 */
@OptIn(UnstableApi::class)
object MediaSources {

    const val NAS_SCHEME = "nas"

    fun uriOf(track: Track): Uri =
        if (track.onNas) Uri.Builder().scheme(NAS_SCHEME).authority("file").appendQueryParameter("p", track.path).build()
        else Uri.fromFile(java.io.File(track.path))

    /** Files and content through Media3's own source; "nas:" through ours. */
    fun factory(context: Context): DataSource.Factory {
        val app = context.applicationContext
        return DataSource.Factory { Switch(DefaultDataSource(app, false), SmbDataSource(app)) }
    }

    private class Switch(private val local: DataSource, private val nas: DataSource) : DataSource {
        private var current: DataSource? = null

        override fun addTransferListener(listener: TransferListener) {
            local.addTransferListener(listener)
            nas.addTransferListener(listener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val source = if (dataSpec.uri.scheme == NAS_SCHEME) nas else local
            current = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = current?.uri

        override fun close() {
            try { current?.close() } finally { current = null }
        }
    }
}

/**
 * A NAS file as a Media3 stream. Reads ahead [CHUNK] bytes at a time, so an
 * audio decoder's many small reads are a few SMB round trips, not hundreds.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val context: Context) : BaseDataSource(/* isNetwork = */ true) {

    private var session: NasSession? = null
    private var reader: NasReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private val chunk = ByteArray(CHUNK)
    private var chunkStart = 0L
    private var chunkLength = 0
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val path = dataSpec.uri.getQueryParameter("p") ?: throw IOException("no NAS path")
        val config = NasStore.load(context) ?: throw IOException("no NAS settings")
        try {
            val s = NasSession.open(config)
            session = s
            val r = s.openRead(path)
            reader = r
            position = dataSpec.position
            if (position > r.size) throw IOException("past the end")
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else r.size - position
        } catch (e: Exception) {
            close()
            // The kind only: a message from smbj carries the NAS's address.
            Log.w(TAG, "nas open failed: ${e.javaClass.simpleName}")
            throw IOException("nas open failed")
        }
        uri = dataSpec.uri
        chunkLength = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val r = reader ?: return C.RESULT_END_OF_INPUT
        if (position < chunkStart || position >= chunkStart + chunkLength) {
            val got = r.read(chunk, 0, minOf(CHUNK.toLong(), remaining).toInt(), position)
            if (got < 0) return C.RESULT_END_OF_INPUT
            chunkStart = position
            chunkLength = got
        }
        val from = (position - chunkStart).toInt()
        val n = minOf(length.toLong(), (chunkLength - from).toLong(), remaining).toInt()
        System.arraycopy(chunk, from, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { reader?.close() }
        runCatching { session?.close() }
        reader = null
        session = null
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    companion object {
        private const val TAG = "KioskMusic"
        const val CHUNK = 256 * 1024
    }
}
