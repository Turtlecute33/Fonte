package app.fonte.source

import app.fonte.data.Asset
import app.fonte.data.Release
import app.fonte.data.TrackedApp
import app.fonte.install.RepoIndex

/**
 * An F-Droid repo that the user adds with its fingerprint. Each check reads entry.jar (a few KB) and checks it with
 * the repo key. When the index digest is the stored one, nothing changed; else Fonte reads the signed index. So the
 * APK digest always comes from the signed index, and the repo gets no ETag that could link the checks.
 */
object Repo : SourceApi {
    override suspend fun check(app: TrackedApp, ctx: Ctx): Check {
        val s = app.source
        val key = s.key ?: error("No repo key")
        val pkg = s.packageName ?: error("No package")
        val index = ctx.index.entry(s.repoAddress, key, s.host)
        if (index.sha256 == Sources.etagFor(app)) return Check.Same
        val versions = ctx.index.read(s.repoAddress, index, s.host) { RepoIndex.versions(it, pkg) }
        return Check.Found(pick(versions, app.prerelease, ctx.abis, s.repoAddress), index.sha256)
    }

    /**
     * The highest version code with no native code or native code for [abis], as the F-Droid client picks it. A
     * version in a release channel ("Beta") counts only with pre-releases on.
     */
    fun pick(versions: List<RepoIndex.Version>, prerelease: Boolean, abis: List<String>, address: String): Release? {
        val v = versions.filter { (prerelease || !it.beta) && (it.abis.isEmpty() || it.abis.any(abis::contains)) }.maxByOrNull { it.code }
            ?: return null
        return Release(
            tag = v.name,
            version = v.name,
            code = v.code,
            date = v.added,
            assets = listOf(Asset(v.file.removePrefix("/"), address + v.file, v.size, v.sha256)),
        )
    }
}
