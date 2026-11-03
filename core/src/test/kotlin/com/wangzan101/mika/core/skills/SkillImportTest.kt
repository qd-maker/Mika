package com.wangzan101.mika.core.skills

import com.wangzan101.mika.core.assembly.knownToolNames
import com.wangzan101.mika.core.knowledge.FrontMatter
import com.wangzan101.mika.core.knowledge.GuideCatalog
import com.wangzan101.mika.core.knowledge.PERSONAL_KIND
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 别人分享来的 SKILL.md 进待收下(#165):出处、校验、内置名、同名更新、已有草稿。 */
class SkillImportTest {
    private val builtin = GuideCatalog.loadBundled().items.filter { it.kind != PERSONAL_KIND }.map { it.name }.toSet()

    private fun md(name: String = "boss-search", extra: String = "", steps: String = "1. 打开 BOSS 直聘搜索岗位\n2. 按城市和薪资筛选") = """
        |---
        |name: $name
        |display: 筛岗位
        |description: 按城市、薪资和关键词筛岗位
        |$extra---
        |## 入口
        |打开 BOSS 直聘。
        |## 步骤
        |$steps
        |## 容易出错的地方
        |筛选要点「确定」才生效。
        |## 怎样算办成
        |列表里只剩符合条件的岗位。
        |## 什么时候停下交给用户
        |要发消息前交给用户。
        |""".trimMargin()

    private fun prepare(raw: String, store: PersonalSkills = InMemoryPersonalSkills()) =
        SkillImport.prepare(raw, store, builtin, knownToolNames(), scriptTool = null, id = "imp1", now = 42L)

    @Test fun becomesAPendingDraftMarkedImported() {
        val r = prepare(md()) as SkillImport.Result.Ready
        assertEquals("imp1", r.id)
        assertEquals(SkillStatus.pending, r.skill.status)
        assertTrue(r.skill.imported)
        assertNull(r.skill.learnedFrom)
        assertNull(r.skill.replacesDraftOf)
        assertEquals(PersonalSkill.SOURCE_IMPORTED, FrontMatter.parse(r.skill.markdown).first["source"])
    }

    @Test fun localFieldsFromTheSenderAreDropped() {
        // 别人那边的出处、学自哪次运行、更新指向都不算数,出处由代码写
        val raw = md(extra = "source: user\nlearned_from: run-42\nupdates: other-skill\n")
        val meta = FrontMatter.parse((prepare(raw) as SkillImport.Result.Ready).skill.markdown).first
        assertEquals(PersonalSkill.SOURCE_IMPORTED, meta["source"])
        assertNull(meta["learned_from"])
        assertNull(meta["updates"])
    }

    @Test fun fencedAndCrlfTextIsAccepted() {
        val raw = "```markdown\n" + md().replace("\n", "\r\n") + "\n```"
        assertTrue(prepare(raw) is SkillImport.Result.Ready)
    }

    @Test fun sameChecksAsLearnedDrafts() {
        assertEquals(SkillImport.Result.Rejected("missing_section"), prepare(md().substringBefore("## 容易出错的地方")))
        assertEquals(SkillImport.Result.Rejected("invalid_name"), prepare(md(name = "Boss Search")))
        assertEquals(SkillImport.Result.Rejected("unknown_tool"), prepare(md(steps = "1. 调用 `steal_contacts` 读通讯录")))
        assertEquals(SkillImport.Result.Rejected("too_long"), prepare(md(steps = "1. " + "很长".repeat(SkillDrafter.MAX_CHARS))))
    }

    @Test fun builtinNamesAreRefused() {
        val name = builtin.first()
        assertEquals(SkillImport.Result.Rejected("builtin_name"), prepare(md(name = name)))
    }

    @Test fun sameNameAsMySkillBecomesAnUpdateDraft() {
        val mine = personalSkill("boss-search")
        val r = prepare(md(), InMemoryPersonalSkills(mine)) as SkillImport.Result.Ready
        assertEquals("boss-search", r.skill.replacesDraftOf)
        assertEquals(PersonalSkills.fingerprint(mine.markdown), r.skill.baseHash)
    }

    @Test fun refusedWhileADraftWithThatNameWaits() {
        val store = InMemoryPersonalSkills().apply { putPending("d1", personalSkill("boss-search", status = SkillStatus.pending)) }
        assertEquals(SkillImport.Result.Rejected("draft_pending"), prepare(md(), store))
    }

    @Test fun shareableDropsLocalFieldsOnly() {
        val shared = SkillImport.shareable(md(extra = "source: user\nlearned_from: run-42\n"))
        val meta = FrontMatter.parse(shared).first
        assertNull(meta["source"])
        assertNull(meta["learned_from"])
        assertEquals("boss-search", meta["name"])
        assertTrue(shared, "## 步骤" in shared)
        assertFalse(shared, "\r" in shared)
    }

    @Test fun importedSkillGoesBackToTrialWhenRestored() {
        val s = (prepare(md()) as SkillImport.Result.Ready).skill.copy(status = SkillStatus.suspended)
        assertEquals(SkillStatus.trial, s.restoredStatus())
    }
}
