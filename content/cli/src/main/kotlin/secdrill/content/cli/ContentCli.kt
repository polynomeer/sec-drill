package secdrill.content.cli

import secdrill.content.BundleDigester
import secdrill.content.BundleSignature
import secdrill.content.BundleSigner
import secdrill.content.ContentBundle
import secdrill.content.ContentValidation
import secdrill.content.UnavailableRuntimeVerifier
import secdrill.kernel.ValidationStatus
import tools.jackson.databind.json.JsonMapper
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/**
 * Commands:
 *   keygen --private-key FILE --key-id ID     write an Ed25519 private key (0600) and print the public key
 *   digest BUNDLE_DIR                         print content, oracle and bundle digests
 *   sign BUNDLE_DIR --private-key FILE --key-id ID
 *   validate BUNDLE_DIR [--trust ID=PUBLIC_KEY]...   static checks; runtime checks are NOT_RUN offline
 *
 * The private key is read from a file and never printed or logged. Exit code 0 means the command succeeded and,
 * for validate, that no check FAILED (INCOMPLETE is expected until a runtime verifier exists).
 */
fun main(args: Array<String>): Unit = exitProcess(ContentCli(System.out, System.err).run(args.toList()))

class ContentCli(private val out: PrintStream, private val err: PrintStream) {
    private val json = JsonMapper.builder().build()

    fun run(args: List<String>): Int = try {
        when (args.firstOrNull()) {
            "keygen" -> keygen(options(args.drop(1)))
            "digest" -> digest(Path.of(args[1]))
            "sign" -> sign(Path.of(args[1]), options(args.drop(2)))
            "validate" -> validate(Path.of(args[1]), args.drop(2))
            else -> usage()
        }
    } catch (error: IndexOutOfBoundsException) {
        usage()
    } catch (error: Exception) {
        err.println("error: ${error.message}")
        1
    }

    private fun usage(): Int {
        err.println("usage: content keygen|digest|sign|validate (see ContentCli.kt)")
        return 2
    }

    private fun options(args: List<String>): Map<String, String> = args.chunked(2).associate { (k, v) -> k.removePrefix("--") to v }

    private fun keygen(options: Map<String, String>): Int {
        val target = Path.of(options.getValue("private-key"))
        require(!Files.exists(target)) { "refusing to overwrite an existing key file" }
        val pair = BundleSigner.generateKeyPair()
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(target, pair.privateKey)
        out.println(json.writeValueAsString(mapOf("keyId" to options.getValue("key-id"), "algorithm" to BundleSigner.ALGORITHM, "publicKey" to pair.publicKey)))
        return 0
    }

    private fun digest(dir: Path): Int {
        out.println(json.writeValueAsString(BundleDigester.digests(ContentBundle.readDirectory(dir, json))))
        return 0
    }

    private fun sign(dir: Path, options: Map<String, String>): Int {
        val digest = BundleDigester.digests(ContentBundle.readDirectory(dir, json)).bundleDigest
        val key = BundleSigner.privateKey(Files.readString(Path.of(options.getValue("private-key"))))
        val signature = BundleSigner.sign(digest, options.getValue("key-id"), key)
        Files.writeString(dir.resolve(ContentBundle.SIGNATURE), json.writeValueAsString(signature))
        out.println(json.writeValueAsString(mapOf("bundleDigest" to digest, "keyId" to signature.keyId)))
        return 0
    }

    private fun validate(dir: Path, rest: List<String>): Int {
        val trusted = rest.chunked(2).filter { it[0] == "--trust" }.associate { (_, value) ->
            value.substringBefore("=") to BundleSigner.publicKey(value.substringAfter("="))
        }
        val signatureFile = dir.resolve(ContentBundle.SIGNATURE)
        val signature = if (Files.exists(signatureFile)) BundleSignature.fromJson(json.readTree(signatureFile.toFile())) else null
        val report = ContentValidation.run(ContentBundle.readDirectory(dir, json), signature, trusted, UnavailableRuntimeVerifier)
        out.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(report))
        return if (report.status == ValidationStatus.FAIL) 1 else 0
    }
}
