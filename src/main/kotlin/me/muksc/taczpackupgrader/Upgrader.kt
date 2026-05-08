package me.muksc.taczpackupgrader

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import org.apache.logging.log4j.LogManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*

@OptIn(ExperimentalPathApi::class)
object Upgrader {
    val LOGGER = LogManager.getLogger(TaCZPackUpgrader.MOD_ID)
    const val SUFFIX = "+1.21.1"

    val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    val conversion: Map<String, Tag> = TaCZPackUpgrader.container.modInfo.owningFile.file
        .findResource("conversion.json").bufferedReader().use {
            val type = object : TypeToken<Map<String, Tag>>() { }.type
            gson.fromJson(it, type)
        }


    fun run(path: Path) {
        if (!path.exists()) return
        val workingDirectory = path.resolveSibling(TaCZPackUpgrader.MOD_ID).apply {
            deleteRecursively()
            createDirectories()
        }
        val backupDirectory = path.resolveSibling("${path.name}+1.20.1").apply {
            createDirectories()
        }

        extractPacks(path, workingDirectory, backupDirectory)
        processPacks(path, backupDirectory)
        workingDirectory.deleteRecursively()
    }

    fun extractPacks(path: Path, workingDirectory: Path, backupDirectory: Path) {
        extractZipPacks(path, workingDirectory, backupDirectory)
        extractJarPacks(path, workingDirectory, backupDirectory)
        workingDirectory.forEachDirectoryEntry { entry ->
            if (!entry.isDirectory()) return@forEachDirectoryEntry
            val directory = path.resolve(entry.name)
            if (directory.exists()) return@forEachDirectoryEntry
            entry.moveTo(directory)
        }
    }

    fun extractZipPacks(path: Path, workingDirectory: Path, backupDirectory: Path) {
        val tempDirectory = workingDirectory.resolve("temp").apply {
            createDirectories()
        }
        for (zip in iterateFilePacks(path) { it.extension == "zip" }) {
            if (!shouldUpgrade(zip)) continue
            LOGGER.info("Extracting zip pack: $zip")
            val backup = backupDirectory.resolve(zip.name)
            if (backup.exists()) backup.deleteRecursively()
            zip.moveTo(backup)

            val directory = tempDirectory.resolve(zip.name)
            unzip(backup, directory)
            for (pack in iterateDirectoryPacks(directory)) {
                pack.moveTo(workingDirectory.resolve(zip.name))
            }
        }
        tempDirectory.deleteRecursively()
    }

    fun extractJarPacks(path: Path, workingDirectory: Path, backupDirectory: Path) {
        val tempDirectory = workingDirectory.resolve("temp").apply {
            createDirectories()
        }
        for (jar in iterateFilePacks(path) { it.extension == "jar" }) {
            LOGGER.warn("Extracting jar pack (may not 100% work): $jar")
            val backup = backupDirectory.resolve(jar.name)
            if (backup.exists()) backup.deleteRecursively()
            jar.moveTo(backup)

            val directory = tempDirectory.resolve(jar.name)
            unzip(backup, directory)
            run { // Common
                val assets = directory.resolve("assets")
                if (!assets.isDirectory()) return@run
                assets.forEachDirectoryEntry { namespace ->
                    val custom = namespace.resolve("custom")
                    if (!custom.isDirectory()) return@forEachDirectoryEntry
                    custom.forEachDirectoryEntry { entry ->
                        if (!entry.isDirectory()) return@forEachDirectoryEntry
                        val name = "${jar.nameWithoutExtension}-${entry.name}.${jar.extension}"
                        entry.moveTo(workingDirectory.resolve(name))
                    }
                }
            }
            run { // Corrin
                val addon = directory.resolve("addon")
                if (!addon.isDirectory()) return@run
                addon.forEachDirectoryEntry { entry ->
                    if (!entry.isDirectory()) return@forEachDirectoryEntry
                    val name = "${jar.nameWithoutExtension}-${entry.name}.${jar.extension}"
                    entry.moveTo(workingDirectory.resolve(name))
                }
            }
            run { // Maxstuff
                val assets = directory.resolve("assets")
                if (!assets.isDirectory()) return@run
                assets.forEachDirectoryEntry { namespace ->
                    val gunpack = namespace.resolve("gunpack")
                    if (!gunpack.isDirectory()) return@forEachDirectoryEntry
                    gunpack.forEachDirectoryEntry { entry ->
                        if (!entry.isDirectory()) return@forEachDirectoryEntry
                        val name = "${jar.nameWithoutExtension}-${entry.name}.${jar.extension}"
                        entry.moveTo(workingDirectory.resolve(name))
                    }
                }
            }
            directory.deleteRecursively()
        }
        tempDirectory.deleteRecursively()
    }

    fun processPacks(path: Path, backupDirectory: Path) {
        for (directory in iterateDirectoryPacks(path)) {
            if (!shouldUpgrade(directory)) continue
            LOGGER.info("Upgrading pack: $directory")
            if (directory.extension != "zip" && directory.extension != "jar") {
                val backup = backupDirectory.resolve(directory.name)
                if (backup.exists()) backup.deleteRecursively()
                directory.copyToRecursively(backup, followLinks = false, overwrite = false)
            }

            val data = directory.resolve("data")
            if (data.isDirectory()) {
                data.forEachDirectoryEntry { namespace ->
                    upgradeBlockDatas(namespace)
                    upgradeLootInjectors(namespace)
                    upgradeRecipes(namespace)
                }
            }

            zip(directory, path.resolve("${directory.nameWithoutExtension}${SUFFIX}.zip"))
            directory.deleteRecursively()
        }
    }

    fun shouldUpgrade(path: Path): Boolean {
        if (path.nameWithoutExtension == "tacz_default_gun") return false
        if (path.nameWithoutExtension.endsWith(SUFFIX)) return false
        return true
    }

    fun iterateFilePacks(path: Path, predicate: (Path) -> Boolean): Iterator<Path> = iterator {
        if (!path.isDirectory()) return@iterator
        path.forEachDirectoryEntry { entry ->
            if (!entry.isRegularFile()) return@forEachDirectoryEntry
            if (!predicate(entry)) return@forEachDirectoryEntry
            yield(entry)
        }
    }

    fun iterateDirectoryPacks(path: Path): Iterator<Path> = iterator {
        if (!path.isDirectory()) return@iterator
        if (path.resolve("gunpack.meta.json").exists()) {
            yield(path)
            return@iterator
        }

        path.forEachDirectoryEntry { entry ->
            yieldAll(iterateDirectoryPacks(entry))
        }
    }


    fun iteratePacks(path: Path, destinationDirectory: Path): Iterator<Path> = iterator {
        if (!shouldUpgrade(path)) return@iterator
        if (path.isRegularFile()) {
            if (path.name == "gunpack.meta.json") yield(initializePack(path.parent, destinationDirectory))
            if (path.extension == "zip") {
                val output = initializePack(path, destinationDirectory)
                yieldAll(iteratePacks(output, destinationDirectory))
            }
            return@iterator
        }

        path.forEachDirectoryEntry { entry ->
            if (entry.isSameFileAs(path)) return@forEachDirectoryEntry
            yieldAll(iteratePacks(entry, destinationDirectory))
        }
    }

    fun initializePack(source: Path, destinationDirectory: Path): Path {
        val destination = destinationDirectory.resolve(source.name).apply {
            createDirectories()
        }
        if (source.isDirectory()) {
            source.copyToRecursively(destination, followLinks = false)
            return destination
        }

        unzip(source, destination)
        return destination
    }

    fun upgradeBlockDatas(path: Path) {
        val blocks = path.resolve("data/blocks")
        if (!blocks.exists()) return
        for (block in Files.walk(blocks)) {
            if (block.isDirectory()) continue
            try {
                val obj = block.bufferedReader().use {
                    gson.fromJson(it, JsonObject::class.java)
                }.apply(::upgradeBlockData)
                block.bufferedWriter().use { gson.toJson(obj, it) }
            } catch (e: Exception) {
                LOGGER.error("Failed to convert block data: $block", e)
                block.deleteExisting()
            }
        }
    }

    fun upgradeBlockData(obj: JsonObject) {
        obj["tabs"]?.asJsonArray?.forEach { tab ->
            val icon = tab.asJsonObject["icon"]?.asJsonObject ?: return@forEach
            upgradeItemStack(icon)
        }
    }

    fun upgradeLootInjectors(path: Path) {
        val injectors = path.resolve("tacz_loot_injectors")
        if (!injectors.exists()) return
        for (injector in Files.walk(injectors)) {
            if (injector.isDirectory()) continue
            try {
                val obj = injector.bufferedReader().use {
                    gson.fromJson(it, JsonObject::class.java)
                }.apply(::upgradeLootInjector)
                injector.bufferedWriter().use { gson.toJson(obj, it) }
            } catch (e: Exception) {
                LOGGER.error("Failed to convert loot injector: $injector", e)
                injector.deleteExisting()
            }
        }
    }

    fun upgradeLootInjector(obj: JsonObject) {
        obj["pools"]?.asJsonArray?.forEach { pool ->
            pool.asJsonObject["entries"]?.asJsonArray?.forEach { entry ->
                entry.asJsonObject["functions"]?.asJsonArray?.forEach { function ->
                    if (function !is JsonObject) return@forEach
                    when (function["function"].asString) {
                        "minecraft:set_nbt" -> {
                            function.add("function", JsonPrimitive("minecraft:set_components"))
                            function.add("components", JsonObject().apply {
                                add("minecraft:custom_data", JsonPrimitive(function["tag"].asString))
                            })
                            function.remove("tag")
                        }
                    }
                }
            }
        }
    }

    fun upgradeRecipes(path: Path) {
        val recipes = path.resolve("recipes")
        if (!recipes.exists()) return
        for (recipe in Files.walk(recipes)) {
            if (recipe.isDirectory()) continue
            try {
                val obj = recipe.bufferedReader().use {
                    gson.fromJson(it, JsonObject::class.java)
                }.apply(::upgradeRecipe)
                recipe.bufferedWriter().use { gson.toJson(obj, it) }
            } catch (e: Exception) {
                LOGGER.error("Failed to convert recipe: $recipe", e)
                recipe.deleteExisting()
            }
        }
        recipes.moveTo(recipes.resolveSibling("recipe"))
    }

    fun upgradeRecipe(obj: JsonObject) {
        when (obj["type"]?.asJsonPrimitive?.asString) {
            "tacz:gun_smith_table_crafting" -> {
                obj["materials"]?.asJsonArray?.forEach { material ->
                    val ingredient = material.asJsonObject["item"]?.asJsonObject ?: return@forEach
                    upgradeIngredient(ingredient)
                }
                obj["result"]?.asJsonObject?.also { result ->
                    if (result["type"]?.asJsonPrimitive?.asString != "custom") return@also
                    val stack = result["item"]?.asJsonObject ?: return@also
                    upgradeItemStack(stack)
                }
            }
            "minecraft:crafting_shaped" -> {
                obj["key"]?.asJsonObject?.also { key ->
                    for (ingredient in key.asMap().values) {
                        upgradeIngredient(ingredient.asJsonObject)
                    }
                }
                obj["result"]?.asJsonObject?.also { stack ->
                    upgradeItemStack(stack)
                }
            }
            "minecraft:crafting_shapeless" -> {
                obj["ingredients"]?.asJsonArray?.forEach { ingredient ->
                    upgradeIngredient(ingredient.asJsonObject)
                }
                obj["result"]?.asJsonObject?.also { stack ->
                    upgradeItemStack(stack)
                }
            }
        }
    }

    fun upgradeIngredient(obj: JsonObject) {
        obj["item"]?.asJsonPrimitive?.also { item ->
            when (obj["type"]?.asJsonPrimitive?.asString) {
                "forge:nbt" -> {
                    obj.add("type", JsonPrimitive("tacz:nbt"))
                    obj.add("partial", JsonPrimitive(false))
                    obj.add("items", item)
                    obj.remove("item")
                }
                "forge:partial_nbt" -> {
                    obj.add("type", JsonPrimitive("tacz:nbt"))
                    obj.add("partial", JsonPrimitive(true))
                    obj.add("items", item)
                    obj.remove("item")
                }
            }
        }
        obj["tag"]?.asJsonPrimitive?.also { tag ->
            val conversion = conversion.getOrDefault(tag.asString, null)
                ?: Tag("tag", tag.asString.replace("forge:", "c:"))
            when (conversion.type) {
                "tag" -> obj.add("tag", JsonPrimitive(conversion.value))
                "item" -> {
                    obj.add("item", JsonPrimitive(conversion.value))
                    obj.remove("tag")
                }
            }
        }
    }

    fun upgradeItemStack(obj: JsonObject) {
        obj["item"]?.asJsonPrimitive?.also { item ->
            obj.add("id", item)
            obj.remove("item")
        }
        obj["nbt"]?.asJsonObject?.also { nbt ->
            obj.add("components", JsonObject().apply {
                add("minecraft:custom_data", nbt)
            })
            obj.remove("nbt")
        }
    }

    fun zip(source: Path, destination: Path, bufferSize: Int = 1024) {
        if (!destination.parent.exists()) destination.createParentDirectories()
        val buffer = ByteArray(bufferSize)
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            Files.walk(source).use { files ->
                for (file in files) {
                    if (!file.isRegularFile()) continue
                    zip.putNextEntry(ZipEntry(file.relativeTo(source).invariantSeparatorsPathString))
                    file.inputStream().buffered().use { input ->
                        var length: Int
                        while (input.read(buffer).also { length = it } > 0) {
                            zip.write(buffer, 0, length)
                        }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    fun unzip(source: Path, destination: Path, bufferSize: Int = 1024) {
        if (!destination.exists()) destination.createDirectories()
        val buffer = ByteArray(bufferSize)
        ZipInputStream(source.inputStream().buffered()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val file = destination.resolve(entry.name)
                if (!file.normalize().startsWith(destination.normalize())) {
                    throw IOException("Entry is outside of the target directory '${entry.name}'")
                }

                if (entry.isDirectory) {
                    if (!file.exists()) file.createDirectories()
                } else {
                    if (!file.parent.exists()) file.createParentDirectories()
                    file.outputStream().buffered().use { output ->
                        var length: Int
                        while (zip.read(buffer).also { length = it } > 0) {
                            output.write(buffer, 0, length)
                        }
                    }
                }
                entry = zip.nextEntry
            }
        }
    }
}