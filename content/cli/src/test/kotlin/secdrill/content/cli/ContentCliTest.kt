package secdrill.content.cli

import secdrill.content.ContentBundle
import secdrill.content.SyntheticBundles
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentCliTest {
    private val json = JsonMapper.builder().build()

    private fun cli(vararg args: String): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = ContentCli(PrintStream(out), PrintStream(err)).run(args.toList())
        return code to (out.toString() + err.toString())
    }

    private fun writeBundle(bundle: ContentBundle): Path {
        val dir = Files.createTempDirectory("bundle")
        Files.writeString(dir.resolve(ContentBundle.MANIFEST), bundle.manifest.toString())
        Files.writeString(dir.resolve(ContentBundle.ORACLE), bundle.oracle.toString())
        (bundle.publicFiles + bundle.privateFiles).forEach { (path, bytes) ->
            dir.resolve(path).also { Files.createDirectories(it.parent) }.let { Files.write(it, bytes) }
        }
        return dir
    }

    @Test
    fun `keygen, sign and validate round trip without printing the private key`() {
        val keyFile = Files.createTempDirectory("keys").resolve("author.key")
        val (keygenCode, keygenOut) = cli("keygen", "--private-key", keyFile.toString(), "--key-id", "author-ci")
        assertEquals(0, keygenCode, keygenOut)
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(keyFile)))
        assertFalse(Files.readString(keyFile) in keygenOut, "private key must not be printed")
        val publicKey = json.readTree(keygenOut)["publicKey"].asString()

        val dir = writeBundle(SyntheticBundles.valid())
        val (signCode, signOut) = cli("sign", dir.toString(), "--private-key", keyFile.toString(), "--key-id", "author-ci")
        assertEquals(0, signCode, signOut)
        val (validateCode, report) = cli("validate", dir.toString(), "--trust", "author-ci=$publicKey")
        assertEquals(0, validateCode, report)
        val tree = json.readTree(report)
        assertEquals("INCOMPLETE", tree["status"].asString(), "offline validation cannot run the runtime checks")
        assertTrue(tree["checks"].values().any { it["name"].asString() == "signature" && it["result"].asString() == "PASS" })

        Files.writeString(dir.resolve("public/README.md"), "tampered")
        assertEquals(1, cli("validate", dir.toString(), "--trust", "author-ci=$publicKey").first)
        assertEquals(1, cli("keygen", "--private-key", keyFile.toString(), "--key-id", "x").first, "existing key files are not overwritten")
    }
}
