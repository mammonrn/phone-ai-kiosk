package com.mammonrn.phoneaikiosk.files

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * The NAS, over SMB 2/3, READ ONLY (0.44.0, Poom: "อ่านอย่างเดียวก่อน").
 *
 * Read only in code, not in intent: files are opened with GENERIC_READ and
 * FILE_OPEN only, and nothing here calls mkdir, rm, rmdir, rename or write.
 * NasReadOnlyTest reads this file and fails if any of those appear.
 *
 * NOTHING ABOUT THE NAS IS LOGGED. Not the address, not the user, never the
 * password. A failure is logged as its [Problem] (a word like LOGON_FAILURE);
 * an exception's message is never logged, because smbj puts the host in it.
 */
data class NasConfig(
    val host: String,
    val port: Int,
    val share: String,
    val user: String,
    val domain: String,
    val password: String,
) {
    /** Serialised for [NasStore], which encrypts it. Also the only place the password is written. */
    fun toJson(): String = JSONObject()
        .put("host", host).put("port", port).put("share", share)
        .put("user", user).put("domain", domain).put("password", password)
        .toString()

    /** What the screen may show: the share name, never the address or the user. */
    override fun toString(): String = "NasConfig(share=$share)"

    companion object {
        const val DEFAULT_PORT = 445

        fun fromJson(json: String): NasConfig? = runCatching {
            val o = JSONObject(json)
            NasConfig(o.getString("host"), o.optInt("port", DEFAULT_PORT), o.getString("share"),
                      o.optString("user"), o.optString("domain"), o.optString("password"))
        }.getOrNull()
    }
}

/**
 * What Poom types into the form, made into a [NasConfig] — or the one thing
 * wrong with it, said so it can be fixed.
 *
 * The address field takes what people paste: "192.168.1.20", "nas.local",
 * "nas.local:4445", "smb://nas/Media" or "\\nas\Media". A share written in
 * the address fills the share field when that is empty. "DOMAIN\user" splits.
 */
object NasForm {

    enum class Problem { NO_ADDRESS, BAD_ADDRESS, BAD_PORT, NO_SHARE }

    sealed class Result {
        class Ok(val config: NasConfig) : Result()
        class Bad(val problem: Problem) : Result()
    }

    fun parse(address: String, share: String, user: String, password: String): Result {
        var rest = address.trim()
        if (rest.isEmpty()) return Result.Bad(Problem.NO_ADDRESS)
        rest = rest.removePrefix("smb://").removePrefix("SMB://").replace('\\', '/').trimStart('/')
        val hostPort = rest.substringBefore('/')
        val sharePath = rest.substringAfter('/', "").trim('/')
        val host: String
        var port = NasConfig.DEFAULT_PORT
        if (hostPort.count { it == ':' } == 1) {
            host = hostPort.substringBefore(':')
            port = hostPort.substringAfter(':').toIntOrNull()?.takeIf { it in 1..65535 }
                ?: return Result.Bad(Problem.BAD_PORT)
        } else {
            host = hostPort
        }
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '@' }) return Result.Bad(Problem.BAD_ADDRESS)
        val shareName = share.trim().trim('/', '\\').ifEmpty { sharePath.substringBefore('/') }
        if (shareName.isEmpty()) return Result.Bad(Problem.NO_SHARE)
        val who = user.trim()
        val (domain, name) = if ('\\' in who) who.substringBefore('\\') to who.substringAfter('\\') else "" to who
        return Result.Ok(NasConfig(host, port, shareName, name, domain, password))
    }
}

/** Why the NAS could not be reached, in the words the screen uses (strings nas_problem_*). */
enum class NasProblem {
    NO_NETWORK,            // the phone is not on WiFi or any network
    LOCAL_NETWORK_DENIED,  // Android's local network permission is off for this app
    HOST_NOT_FOUND,        // the name does not resolve
    NO_ANSWER,             // nothing answered: off, asleep, another network, or a firewall
    LOGON_FAILURE,         // user name or password wrong
    ACCOUNT_BLOCKED,       // disabled, locked out, password expired
    SHARE_NOT_FOUND,
    ACCESS_DENIED,
    PATH_NOT_FOUND,
    PROTOCOL,              // answered, but not in SMB 2/3 (an SMB1-only NAS, or not a NAS)
    OTHER;

    companion object {
        /**
         * The problem behind [error]. Reads the exception's type and SMB
         * status only; a message is looked at for EPERM alone, never logged.
         */
        fun of(error: Throwable): NasProblem {
            var e: Throwable? = error
            var depth = 0
            while (e != null && depth < 8) {
                when (e) {
                    is SMBApiException -> return ofStatus(e.statusCode)
                    is java.net.UnknownHostException -> return HOST_NOT_FOUND
                    is java.net.SocketTimeoutException, is java.net.NoRouteToHostException,
                    is java.net.PortUnreachableException -> return NO_ANSWER
                    is SecurityException -> return LOCAL_NETWORK_DENIED
                    is java.net.ConnectException, is java.net.SocketException -> {
                        val m = e.message.orEmpty()
                        return if ("EPERM" in m || "not permitted" in m) LOCAL_NETWORK_DENIED
                        else if ("ENETUNREACH" in m) NO_NETWORK
                        else NO_ANSWER
                    }
                    is com.hierynomus.protocol.transport.TransportException -> {
                        // A TransportException around a socket error is that
                        // error; around nothing it is a server that did not
                        // speak SMB 2/3.
                        if (e.cause == null) return PROTOCOL
                    }
                }
                e = e.cause
                depth += 1
            }
            return if (error is com.hierynomus.smbj.common.SMBRuntimeException) PROTOCOL else OTHER
        }

        // NTSTATUS numbers ([MS-ERREF] 2.3.1). Written out because smbj's
        // NtStatus enum names only some of them.
        private const val LOGON_FAILURE_CODE = 0xC000006DL
        private const val WRONG_PASSWORD = 0xC000006AL
        private const val NO_SUCH_USER = 0xC0000064L
        private val BLOCKED = setOf(
            0xC0000072L, // ACCOUNT_DISABLED
            0xC0000234L, // ACCOUNT_LOCKED_OUT
            0xC0000071L, // PASSWORD_EXPIRED
            0xC000006EL, // ACCOUNT_RESTRICTION
            0xC0000193L, // ACCOUNT_EXPIRED
            0xC0000224L, // PASSWORD_MUST_CHANGE
        )

        fun ofStatus(status: Long): NasProblem = when (status) {
            LOGON_FAILURE_CODE, WRONG_PASSWORD, NO_SUCH_USER -> LOGON_FAILURE
            in BLOCKED -> ACCOUNT_BLOCKED
            NtStatus.STATUS_BAD_NETWORK_NAME.value -> SHARE_NOT_FOUND
            NtStatus.STATUS_ACCESS_DENIED.value -> ACCESS_DENIED
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND.value, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND.value,
            NtStatus.STATUS_NOT_FOUND.value -> PATH_NOT_FOUND
            NtStatus.STATUS_NOT_SUPPORTED.value -> PROTOCOL
            else -> OTHER
        }
    }
}

/** One thing in a NAS folder. [path] is relative to the share, with backslashes. */
data class NasEntry(val name: String, val path: String, val folder: Boolean, val size: Long, val modifiedMs: Long)

/**
 * One connection to the share, opened for one action (a folder listed, a file
 * copied) and closed after it. Every call blocks: the screen calls it from its
 * worker thread, never the main one.
 */
class NasSession private constructor(
    private val client: SMBClient,
    private val connection: Connection,
    private val session: Session,
    private val share: DiskShare,
) : Closeable {

    /** The folder's contents, folders first, without "." and "..". [path] "" is the share's top. */
    fun list(path: String): List<NasEntry> = share.list(path)
        .filter { it.fileName != "." && it.fileName != ".." }
        .map { info ->
            val folder = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            NasEntry(info.fileName, if (path.isEmpty()) info.fileName else "$path\\${info.fileName}",
                     folder, info.endOfFile, info.lastWriteTime.toEpochMillis())
        }
        .sortedWith(compareBy<NasEntry>({ !it.folder }, { it.name.lowercase() }))

    /**
     * Copies a file from the NAS to [target] on the phone — reading only, on
     * the NAS side. A stopped or failed copy removes the partial file.
     */
    fun download(entry: NasEntry, target: File, work: FileOps.Work) {
        work.totalFiles = 1
        work.totalBytes = entry.size
        try {
            share.openFile(entry.path, EnumSet.of(AccessMask.GENERIC_READ), null,
                           SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null).use { remote ->
                remote.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            work.check()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            work.doneBytes += read
                        }
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (entry.modifiedMs > 0) target.setLastModified(entry.modifiedMs)
        work.doneFiles = 1
    }

    override fun close() {
        runCatching { share.close() }
        runCatching { session.close() }
        runCatching { connection.close() }
        runCatching { client.close() }
    }

    companion object {
        /**
         * Connects, signs in and opens the share. NTLM only, with smbj's
         * BouncyCastle provider, both set explicitly: smbj's defaults also
         * try Kerberos (SPNEGO through GSS classes Android does not have)
         * and a JCE provider Android's JCE lacks MD4 for.
         */
        fun open(config: NasConfig): NasSession {
            val smb = SmbConfig.builder()
                .withSecurityProvider(BCSecurityProvider())
                .withAuthenticators(NtlmAuthenticator.Factory())
                .withDfsEnabled(false)
                .withSoTimeout(15, TimeUnit.SECONDS)
                .withTimeout(20, TimeUnit.SECONDS)
                .build()
            val client = SMBClient(smb)
            try {
                val connection = client.connect(config.host, config.port)
                val auth = if (config.user.isEmpty()) AuthenticationContext.guest()
                           else AuthenticationContext(config.user, config.password.toCharArray(), config.domain)
                val session = connection.authenticate(auth)
                val share = session.connectShare(config.share) as? DiskShare
                    ?: throw IOException("not a disk share")
                return NasSession(client, connection, session, share)
            } catch (e: Exception) {
                runCatching { client.close() }
                throw e
            }
        }
    }
}

/**
 * The NAS settings, encrypted (Poom: "เก็บรหัสผ่านอย่างปลอดภัย ห้ามเก็บเป็นข้อความธรรมดา").
 *
 * The whole record — address, user and password — is sealed with AES-256-GCM
 * under a key in the Android Keystore (auth/SecretBox, the identity check's
 * box) and written to noBackupFilesDir, which Android never backs up. The key
 * cannot be read out of the phone, so neither can the password: not by adb,
 * not from a backup, not from the file.
 */
object NasStore {
    private const val FILE = "nas.bin"

    fun load(context: android.content.Context): NasConfig? {
        val file = File(context.noBackupFilesDir, FILE)
        if (!file.isFile) return null
        return runCatching {
            NasConfig.fromJson(String(com.mammonrn.phoneaikiosk.auth.SecretBox.open(file.readBytes()), Charsets.UTF_8))
        }.getOrNull()
    }

    fun save(context: android.content.Context, config: NasConfig) {
        val sealed = com.mammonrn.phoneaikiosk.auth.SecretBox.seal(config.toJson().toByteArray(Charsets.UTF_8))
        val file = File(context.noBackupFilesDir, FILE)
        val temp = File(context.noBackupFilesDir, "$FILE.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(file)) {
            file.writeBytes(sealed)
            temp.delete()
        }
    }

    fun delete(context: android.content.Context) {
        File(context.noBackupFilesDir, FILE).delete()
    }
}
