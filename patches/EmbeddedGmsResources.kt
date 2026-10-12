package es.calma.youtube.patches

import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

private object EmbeddedGmsResources

/** Explicit experimental integration only; ordinary release builds are unchanged. */
val embeddedGmsResourcesPatch = resourcePatch(name = "YouTube Calma experimental embedded GMS resources") {
    compatibleWith("app.revanced.android.youtube"("20.40.45"))
    dependsOn(packagePatch)
    execute {
        if (System.getProperty("calma.embeddedGms") != "true") return@execute
        fun input(path: String) = checkNotNull(EmbeddedGmsResources::class.java.getResourceAsStream("/embedded-res/$path")) { "Missing embedded resource: $path" }
        val fragment = input("manifest-fragment.xml").use {
            DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(it)
        }
        document("AndroidManifest.xml").use { xml ->
            val manifest = xml.documentElement
            val sdk = manifest.getElementsByTagName("uses-sdk").item(0) as? Element
                ?: xml.createElement("uses-sdk").also { manifest.insertBefore(it, manifest.firstChild) }
            // The experimental runtime/D8 contract starts at Android 11. Never
            // advertise installation on older systems that it cannot initialize.
            val minimum = sdk.getAttribute("android:minSdkVersion").toIntOrNull() ?: 1
            if (minimum < 30) sdk.setAttribute("android:minSdkVersion", "30")
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val children = fragment.documentElement.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i) as? Element ?: continue
                if (child.tagName == "application") {
                    application.setAttribute("android:appComponentFactory", "inhouse.microg.host.YouTubeComponentFactory")
                    val additions = child.childNodes
                    for (j in 0 until additions.length) if (additions.item(j) is Element) {
                        val addition = additions.item(j) as Element
                        val name = addition.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                        val existing = application.getElementsByTagName(addition.tagName)
                        check((0 until existing.length).none { (existing.item(it) as Element).getAttribute("android:name") == name }) { "Embedded component/metadata collision: $name" }
                        application.appendChild(xml.importNode(addition, true))
                    }
                } else {
                    val name = child.getAttributeNS("http://schemas.android.com/apk/res/android", "name")
                    val existing = manifest.getElementsByTagName(child.tagName)
                    if ((0 until existing.length).none { (existing.item(it) as Element).getAttribute("android:name") == name }) {
                        manifest.appendChild(xml.importNode(child, true))
                    }
                }
            }
        }
        val files = input("files.txt").bufferedReader().use { it.readLines().filter(String::isNotBlank) }
        for (path in files) {
            check((path.startsWith("res/") || path.startsWith("assets/")) && !path.contains(".."))
            if (path == "res/values/inhouse_gms_strings.xml") {
                // Morphe allocates new values through the existing type document.
                // A separate values file is not part of its decoded entry map.
                val additions = input(path).use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
                document("res/values/strings.xml").use { xml ->
                    val nodes = additions.documentElement.childNodes
                    for (i in 0 until nodes.length) if (nodes.item(i) is Element) {
                        val item = nodes.item(i) as Element
                        val existing = xml.getElementsByTagName("string")
                        check((0 until existing.length).none { (existing.item(it) as Element).getAttribute("name") == item.getAttribute("name") })
                        xml.documentElement.appendChild(xml.importNode(item, true))
                    }
                }
                continue
            }
            val target = get(path); check(!target.exists()) { "Embedded resource collision: $path" }
            target.parentFile.mkdirs()
            input(path).use { source -> target.outputStream().use { source.copyTo(it) } }
        }
        app.morphe.patcher.resource.PublicXmlManager(get("res/values/public.xml")).use {
            it.createPublicId("string", "inhouse_gms_account_label")
            it.createPublicId("xml", "inhouse_gms_authenticator")
            it.createPublicId("xml", "inhouse_gms_file_paths")
        }
    }
}
