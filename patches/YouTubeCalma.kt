package es.calma.youtube.patches

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.Opcode
import org.w3c.dom.Element

private const val PKG = "app.revanced.android.youtube"
private const val EXT = "Les/calma/youtube/nativeapp/CalmaBootstrap;"
private object Resources

val packagePatch = resourcePatch(name = "YouTube Calma package") {
    compatibleWith(PKG("20.40.45"))
    execute {
        check(packageMetadata.versionCode == "1557278144")
        document("AndroidManifest.xml").use { xml ->
            val manifest = xml.documentElement
            manifest.setAttribute("android:versionCode", RELEASE_VERSION_CODE.toString())
            val app = manifest.getElementsByTagName("application").item(0) as Element
            app.setAttribute("android:icon", "@mipmap/inhouse_launcher")
            app.setAttribute("android:roundIcon", "@mipmap/inhouse_launcher")
            // Cover every launcher alias, including the alias already enabled on upgrades.
            for (tag in listOf("activity", "activity-alias")) {
                val entries = app.getElementsByTagName(tag)
                for (i in 0 until entries.length) {
                    val entry = entries.item(i) as Element
                    val categories = entry.getElementsByTagName("category")
                    if ((0 until categories.length).any { (categories.item(it) as Element).getAttribute("android:name") == "android.intent.category.LAUNCHER" }) {
                        entry.setAttribute("android:icon", "@mipmap/inhouse_launcher")
                        entry.setAttribute("android:roundIcon", "@mipmap/inhouse_launcher")
                    }
                }
            }
            fun add(parent: Element, tag: String, attrs: Map<String,String>): Element {
                val node = xml.createElement(tag)
                attrs.forEach { (key, value) -> node.setAttribute("android:$key", value) }
                parent.appendChild(node)
                return node
            }
            add(manifest, "uses-permission", mapOf("name" to "android.permission.REQUEST_INSTALL_PACKAGES"))
            add(app,"activity",mapOf("name" to "es.calma.youtube.UpdateActivity", "exported" to "false", "theme" to "@style/CalmaUpdatePopupTheme"))
            add(app,"activity",mapOf("name" to "es.calma.youtube.nativeapp.CalmaSettingsActivity", "exported" to "false", "theme" to "@style/CalmaSettingsTheme"))
            // YouTube already owns .fileprovider. Keep Photos' separate update
            // authority so PackageManager resolves our cache paths, not theirs.
            val authority="$PKG.update-provider"
            val providers=app.getElementsByTagName("provider")
            for (i in 0 until providers.length) {
                check(authority !in (providers.item(i) as Element).getAttribute("android:authorities").split(';'))
            }
            val provider=add(app,"provider",mapOf("name" to "es.calma.vendor.androidx.core.content.FileProvider", "authorities" to authority, "exported" to "false", "grantUriPermissions" to "true"))
            add(provider,"meta-data",mapOf("name" to "android.support.FILE_PROVIDER_PATHS", "resource" to "@xml/calma_update_file_paths"))
            add(app,"meta-data",mapOf("name" to "es.calma.version", "value" to RELEASE_VERSION))
            // Enable the requested original YouTube icon/name before the first launch.
            val aliases = app.getElementsByTagName("activity-alias")
            for (i in 0 until aliases.length) {
                val alias = aliases.item(i) as Element
                val rawName = alias.getAttribute("android:name")
                val name = if(rawName.startsWith(".")) PKG + rawName else rawName
                if (name.startsWith("$PKG.revanced_")) alias.setAttribute("android:enabled", (name == "$PKG.revanced_original_1").toString())
            }
        }
        for (path in listOf("assets/inhouse-logo.png", "res/drawable/inhouse_settings_icon.xml", "res/drawable-nodpi/inhouse_launcher_art.png", "res/drawable/inhouse_launcher_foreground.xml", "res/drawable/inhouse_launcher_background.xml", "res/mipmap-anydpi/inhouse_launcher.xml", "res/mipmap-anydpi-v26/inhouse_launcher.xml", "assets/calma-preferences.json", "assets/updates/index.html", "assets/updates/updater.js", "assets/updates/updater.css", "assets/updates/host.css", "assets/updates/host.js", "assets/updates/config.json", "res/xml/calma_update_file_paths.xml")) {
            val target=get(path);target.parentFile.mkdirs()
            Resources::class.java.getResourceAsStream("/calma-res/$path")!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        // Insert beside the real ReVanced entry in both native settings layouts.
        for(name in listOf("settings_fragment", "settings_fragment_cairo")) document("res/xml/$name.xml").use { xml ->
            val intents=xml.getElementsByTagName("intent")
            val original=(0 until intents.length).map { intents.item(it) as Element }
                .single { it.getAttribute("android:data")=="revanced_settings_intent" }.parentNode as Element
            val entry=xml.createElement("Preference")
            for(attribute in listOf("android:layout","android:iconSpaceReserved","app:iconSpaceReserved")) {
                if(original.hasAttribute(attribute))entry.setAttribute(attribute,original.getAttribute(attribute))
            }
            entry.setAttribute("android:icon","@drawable/inhouse_settings_icon")
            entry.setAttribute("android:key","inhouse_youtube_settings")
            entry.setAttribute("android:title","Inhouse YouTube settings")
            val intent=xml.createElement("intent")
            intent.setAttribute("android:targetPackage",PKG)
            intent.setAttribute("android:targetClass","es.calma.youtube.nativeapp.CalmaSettingsActivity")
            entry.appendChild(intent)
            original.parentNode.insertBefore(entry,original.nextSibling)
        }
        // Keep the manual update entry in the APK's own native settings screen.
        for (name in listOf("revanced_prefs", "revanced_prefs_icons", "revanced_prefs_icons_bold")) document("res/xml/$name.xml").use { xml ->
            val entry=xml.createElement("Preference")
            entry.setAttribute("android:key","calma_updates")
            entry.setAttribute("android:title","Actualizar aplicación")
            val intent=xml.createElement("intent")
            intent.setAttribute("android:targetPackage",PKG)
            intent.setAttribute("android:targetClass","es.calma.youtube.UpdateActivity")
            entry.appendChild(intent)
            xml.documentElement.appendChild(entry)
        }
        for(folder in listOf("values","values-night")) {
            val path="res/$folder/styles.xml"
            val additions=Resources::class.java.getResourceAsStream("/calma-res/$path")!!.use { javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            if(!get(path).exists()) { get(path).parentFile.mkdirs();get(path).writeText("<resources/>") }
            document(path).use { xml ->
                val nodes=additions.documentElement.childNodes
                for (i in 0 until nodes.length) if(nodes.item(i) is Element) xml.documentElement.appendChild(xml.importNode(nodes.item(i),true))
            }
        }
        app.morphe.patcher.resource.PublicXmlManager(get("res/values/public.xml")).use {
            it.createPublicId("style","CalmaUpdatePopupTheme")
            it.createPublicId("style","CalmaSettingsTheme")
            it.createPublicId("xml","calma_update_file_paths")
            it.createPublicId("drawable", "inhouse_settings_icon")
            it.createPublicId("drawable", "inhouse_launcher_art")
            it.createPublicId("drawable", "inhouse_launcher_foreground")
            it.createPublicId("drawable", "inhouse_launcher_background")
            it.createPublicId("mipmap", "inhouse_launcher")
        }
    }
}

val behaviorPatch = bytecodePatch(name = "YouTube Calma subscriptions and navigation") {
    compatibleWith(PKG("20.40.45"))
    dependsOn(packagePatch)
    extendWith("calma-extension.dex")
    execute {
        fun method(type: String, name: String, parameters: List<String>) =
            mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map { p -> p.toString() } == parameters }
        method("Lapp/revanced/extension/shared/Utils;", "setContext", listOf("Landroid/content/Context;")).addInstructions(0,
            "invoke-static/range {p0 .. p0}, $EXT->initialize(Landroid/content/Context;)V")
        // Reject unusable adaptive formats before the existing client fallback
        // loop accepts a response. No alternate patch bundle is applied.
        val streamResponse = method("Lapp/revanced/extension/shared/spoof/requests/StreamingDataRequest;",
            "buildPlayerResponseBuffer", listOf("Lapp/revanced/extension/shared/spoof/ClientType;", "Ljava/net/HttpURLConnection;"))
        val responseReturns = streamResponse.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_OBJECT }.toList()
        check(responseReturns.isNotEmpty())
        for ((index, instruction) in responseReturns.asReversed()) {
            val register = (instruction as OneRegisterInstruction).registerA
            streamResponse.addInstructions(index, """
                invoke-static/range {v$register .. v$register}, Les/calma/youtube/nativeapp/CalmaStreamResponse;->clean([B)[B
                move-result-object v$register
            """.trimIndent())
        }
        val routes = method("Lapp/revanced/extension/shared/spoof/requests/PlayerRoutes;", "<clinit>", emptyList())
        val playerRoute = routes.implementation!!.instructions.withIndex().single { (_, instruction) ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == "player?fields=streamingData&alt=proto"
        }
        val routeRegister = (playerRoute.value as OneRegisterInstruction).registerA
        routes.replaceInstruction(playerRoute.index,
            "const-string v$routeRegister, \"player?fields=playabilityStatus,streamingData&alt=proto\"")

        for((name,params) in listOf(
            "navigationTabCreated" to listOf("Lapp/revanced/extension/youtube/shared/NavigationBar\$NavigationButton;","Landroid/view/View;"),
            "switchCreateWithNotificationButton" to listOf("Ljava/lang/String;")
        )) {
            val target=method("Lapp/revanced/extension/youtube/patches/NavigationBarPatch;",name,params)
            check(target.implementation!!.registerCount > params.size)
            val disabled=if(name=="navigationTabCreated") "return-void" else "return-object p0"
            target.addInstructionsWithLabels(0,"""
                invoke-static {}, Les/calma/youtube/nativeapp/CalmaSwitch;->enabled()Z
                move-result v0
                if-nez v0, :inhouse_enabled
                $disabled
            """.trimIndent(),ExternalLabel("inhouse_enabled",target.implementation!!.instructions.first()))
        }
        val home = method("Lehy;", "K", listOf("Laogf;"))
        val hook = home.implementation!!.instructions.withIndex().single { (_, ins) ->
            val ref=(ins as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lapp/revanced/extension/youtube/patches/ChangeStartPagePatch;" && ref.name == "overrideBrowseId"
        }
        val result=home.implementation!!.instructions[hook.index+1] as OneRegisterInstruction
        home.replaceInstruction(hook.index,"invoke-static {v${result.registerA}}, $EXT->homeBrowseId(Ljava/lang/String;)Ljava/lang/String;")
        // Bind a real notification model before the controller assigns pivot indices.
        val pivots = method("Lpgu;", "O", listOf("Lafpw;"))
        val parsed = pivots.implementation!!.instructions.withIndex().single { (_, ins) ->
            val ref=(ins as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lphb;" && ref.name == "h" && ref.returnType == "Larbh;"
        }
        val pivotResult=pivots.implementation!!.instructions[parsed.index+1] as OneRegisterInstruction
        pivots.addInstructions(parsed.index+2,"""
            invoke-static {v${pivotResult.registerA}}, Les/calma/youtube/nativeapp/NativeInbox;->tabs(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v${pivotResult.registerA}
            check-cast v${pivotResult.registerA}, Larbh;
        """.trimIndent())
        // This APK carries the navigation settings but omitted their callback.
        method("Lapp/revanced/extension/youtube/shared/NavigationBar;", "navigationTabCreatedCallback", listOf("Lapp/revanced/extension/youtube/shared/NavigationBar\$NavigationButton;","Landroid/view/View;")).addInstructions(0,
            "invoke-static {p0, p1}, Lapp/revanced/extension/youtube/patches/NavigationBarPatch;->navigationTabCreated(Lapp/revanced/extension/youtube/shared/NavigationBar\$NavigationButton;Landroid/view/View;)V")
        // Existing client-context hooks already feed this method on browse requests.
        method("Lapp/revanced/extension/youtube/patches/litho/AdsFilter;", "hideAds", listOf("Ljava/lang/String;")).addInstructions(0,"""
            invoke-static {p0}, Lapp/revanced/extension/youtube/patches/NavigationBarPatch;->switchCreateWithNotificationButton(Ljava/lang/String;)Ljava/lang/String;
            move-result-object p0
        """.trimIndent())
        val toolbar = method("Lnnn;", "l", listOf("Landroid/view/MenuItem;", "Landroid/content/Context;"))
        val icon = toolbar.implementation!!.instructions.withIndex().single { (_, ins) ->
            val ref=(ins as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == "Lanmf;" && ref.name == "a" && ref.parameterTypes.map { it.toString() } == listOf("Laxlk;")
        }
        val iconRegister = (icon.value as FiveRegisterInstruction).registerD
        toolbar.addInstructions(icon.index,"invoke-static {v$iconRegister, p1}, $EXT->toolbarItem(Ljava/lang/Enum;Landroid/view/MenuItem;)V")
        method("Lapp/revanced/extension/youtube/patches/ChangeStartPagePatch;", "overrideIntentAction", listOf("Landroid/content/Intent;")).addInstructions(0,
            "invoke-static {p0}, $EXT->launchSubscriptions(Landroid/content/Intent;)V")
        method("Lapp/revanced/extension/youtube/patches/PlayerTypeHookPatch;", "setPlayerType", listOf("Ljava/lang/Enum;")).addInstructions(0,
            "invoke-static {p0}, Les/calma/youtube/nativeapp/CalmaWatch;->playerType(Ljava/lang/Enum;)V")
        method("Lapp/revanced/extension/shared/patches/litho/LithoFilterPatch;", "isFiltered", listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/StringBuilder;")).addInstructions(0,"""
            invoke-static {p0, p3}, Les/calma/youtube/nativeapp/CalmaWatch;->filter(Ljava/lang/String;Ljava/lang/StringBuilder;)Z
            move-result v0
            if-eqz v0, :calma_original_filter
            return v0
            :calma_original_filter
            nop
        """.trimIndent())
    }
}
