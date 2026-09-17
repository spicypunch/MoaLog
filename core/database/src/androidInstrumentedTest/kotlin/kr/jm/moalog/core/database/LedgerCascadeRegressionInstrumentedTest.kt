package kr.jm.moalog.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import kr.jm.moalog.core.model.PlanItemType
import kr.jm.moalog.core.model.YearMonthKey
import kr.jm.moalog.core.model.SalaryIncome
import kr.jm.moalog.core.model.SalaryAllocationCategory
import kr.jm.moalog.core.model.SalaryAllocationChild
import kr.jm.moalog.core.model.SalaryAllocationMethod
import kr.jm.moalog.core.model.SalaryAllocationGrandchild
import kr.jm.moalog.core.model.childTotal
import kr.jm.moalog.core.model.FixedCostCheckItem
import kr.jm.moalog.core.model.ExpenseRecord
import kr.jm.moalog.core.model.ExpenseCategory
import kr.jm.moalog.core.model.LocalDateKey
import kr.jm.moalog.core.model.AssetGrowthRule
import kr.jm.moalog.core.model.AssetItem
import kr.jm.moalog.core.model.AssetKind
import kr.jm.moalog.core.model.AssetType
import kr.jm.moalog.core.model.AssetValuation
import kr.jm.moalog.core.model.MonthlyPlanItem
import kr.jm.moalog.core.model.PlanItemStatus
import kr.jm.moalog.core.model.PlanCatalogItem

@RunWith(AndroidJUnit4::class)
class LedgerCascadeRegressionInstrumentedTest {
    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun customExpenseCategoryIdsRemainUniqueAcrossLedgers() = runBlocking {
        val directory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val file = File(directory, "moalog-category-ledgers-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, file.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "첫째", 2026, null))
            database.ledgerSetupDao().upsertLedger(LedgerEntity(2L, "둘째", 2026, null))

            val first = database.expenseDao().createCategory(1L, "식비")
            val second = database.expenseDao().createCategory(2L, "식비")

            assertNotEquals(first.id, second.id)
            assertEquals(1L, first.ledgerId)
            assertEquals(2L, second.ledgerId)
        } finally {
            database.close()
            cleanupRoomFiles(file)
        }
    }

    @Test
    fun catalogAndArchivedReferencedCategorySurviveDatabaseReopen() = runBlocking {
        val directory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val file = File(directory, "moalog-catalog-${System.nanoTime()}.db")
        fun open() = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, file.absolutePath))
        var database = open()
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            var plans = createPlanLocalDataSource(database)
            val january = YearMonthKey(2026, 1)
            val sourceId = plans.save(
                MonthlyPlanItem(
                    type = PlanItemType.Savings,
                    attributionMonth = january,
                    name = "여행 적금",
                    amountWon = 100_000,
                    category = "예적금",
                    status = PlanItemStatus.Confirmed,
                    ownerMemberOrder = null,
                    memo = null,
                    includePurposeAccount = true,
                    includeNetSavings = false,
                ),
            )
            val source = requireNotNull(plans.find(sourceId))
            plans.copyToMonths(
                PlanCopyRequest(january, PlanItemType.Savings, sourceId, listOf(YearMonthKey(2026, 2)), ExistingPlanPolicy.Overwrite),
            )
            plans.setCatalogArchived(source.catalogId, true)

            var expenses = createExpenseLocalDataSource(database)
            expenses.ensureDefaultCategories()
            val category = expenses.observeAllCategories().first().first()
            expenses.saveRecord(
                ExpenseRecord(
                    categoryId = category.id,
                    categoryName = category.name,
                    attributionMonth = january,
                    actualDate = null,
                    detail = "과거 기록",
                    amountWon = 1_000,
                    overspent = false,
                ),
            )
            expenses.setCategoryArchived(category.id, true)
            database.close()

            database = open()
            plans = createPlanLocalDataSource(database)
            val januaryItem = plans.observeMonth(january).first().single()
            val februaryItem = plans.observeMonth(YearMonthKey(2026, 2)).first().single()
            assertEquals(januaryItem.catalogId, februaryItem.catalogId)
            assertEquals(true, plans.observeCatalog().first().single { it.id == januaryItem.catalogId }.archived)
            expenses = createExpenseLocalDataSource(database)
            assertEquals(true, expenses.observeMonth(january).first().categories.single { it.id == category.id }.archived)
            assertEquals(false, database.expenseDao().observeCategories().first().any { it.id == category.id })
        } finally {
            database.close()
            cleanupRoomFiles(file)
        }
    }

    @Test
    fun catalogReorderOnlyRequiresActiveItemsOfTheSelectedType() = runBlocking {
        val directory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val file = File(directory, "moalog-catalog-order-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, file.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val source = createPlanLocalDataSource(database)
            val first = source.saveCatalog(PlanCatalogItem(type = PlanItemType.Income, classification = "급여", name = "월급", ownerMemberOrder = 0))
            val second = source.saveCatalog(PlanCatalogItem(type = PlanItemType.Income, classification = "급여", name = "상여", ownerMemberOrder = 0))
            val savings = source.saveCatalog(PlanCatalogItem(type = PlanItemType.Savings, classification = "예적금", name = "적금", ownerMemberOrder = null))

            source.reorderCatalog(listOf(second, first))

            val catalog = source.observeCatalog().first()
            assertEquals(listOf(second, first), catalog.filter { it.type == PlanItemType.Income }.sortedBy { it.displayOrder }.map { it.id })
            assertEquals(savings, catalog.single { it.type == PlanItemType.Savings }.id)
        } finally {
            database.close()
            cleanupRoomFiles(file)
        }
    }

    @Test
    fun upsertLedgerKeepsMembersCategoriesAndExpenses() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(
            Room.databaseBuilder(
                targetContext,
                MoaLogDatabase::class.java,
                databaseFile.absolutePath,
            ),
        )

        try {
            val ledgerDao = database.ledgerSetupDao()
            val expenseDao = database.expenseDao()

            ledgerDao.upsertLedger(LedgerEntity(id = 1L, name = "초기", baseYear = 2027, annualSavingsTargetWon = 1_000_000L))
            ledgerDao.insertMembers(
                listOf(
                    LedgerMemberEntity(ledgerId = 1L, displayName = "A", displayOrder = 0),
                    LedgerMemberEntity(ledgerId = 1L, displayName = "B", displayOrder = 1),
                ),
            )
            expenseDao.insertCategories(
                listOf(
                    ExpenseCategoryEntity(
                        id = "cat-food",
                        ledgerId = 1L,
                        name = "식비",
                        displayOrder = 0,
                    ),
                ),
            )
            val recordId = expenseDao.insertRecord(
                ExpenseRecordEntity(
                    ledgerId = 1L,
                    categoryId = "cat-food",
                    attributionYear = 2027,
                    attributionMonth = 9,
                    actualDate = "2027-09-02",
                    detail = "저녁",
                    amountWon = 12_000,
                    overspent = false,
                ),
            )

            val before = ledgerDao.observe(1L).first()
            val beforeCategories = expenseDao.observeCategories(1L).first()
            val beforeRecords = expenseDao.observeRecords(2027, 9, 1L).first()

            assertNotNull(before)
            assertEquals("초기", before!!.ledger.name)
            assertEquals(2, before.members.size)
            assertEquals(1, beforeCategories.size)
            assertEquals(1, beforeRecords.size)

            ledgerDao.upsertLedger(LedgerEntity(id = 1L, name = "갱신", baseYear = 2027, annualSavingsTargetWon = 500_000L))

            val after = ledgerDao.observe(1L).first()
            val afterCategories = expenseDao.observeCategories(1L).first()
            val afterRecords = expenseDao.observeRecords(2027, 9, 1L).first()
            val savedRecord = expenseDao.findRecord(recordId)

            assertNotNull(after)
            assertEquals("갱신", after!!.ledger.name)
            assertEquals(2, after.members.size)
            assertEquals(1, afterCategories.size)
            assertEquals("cat-food", afterCategories.single().id)
            assertEquals(1, afterRecords.size)
            assertEquals("cat-food", afterRecords.single().categoryId)
            assertEquals(12_000L, afterRecords.single().amountWon)
            assertNotNull(savedRecord)
            assertEquals(recordId, savedRecord!!.id)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun monthlyPlanPersistsMissingZeroAndNegativeSavingsSeparately() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-plan-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val dao = database.planDao()
            dao.save(planEntity(name = "미입력", amount = null, type = "FixedExpense"))
            dao.save(planEntity(name = "0원", amount = 0, type = "Income"))
            dao.save(planEntity(name = "인출", amount = -10_000, type = "Savings"))
            val rows = dao.observeMonth(2026, 5).first()
            assertEquals(3, rows.size)
            assertEquals(null, rows.first { it.name == "미입력" }.amountWon)
            assertEquals(0L, rows.first { it.name == "0원" }.amountWon)
            assertEquals(-10_000L, rows.first { it.name == "인출" }.amountWon)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun copyPlanToMonthsKeepsOrOverwritesConflictsAtomically() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-plan-copy-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val dao = database.planDao()
            val sourceId = dao.save(planEntity(name = "관리비", amount = null, type = "FixedExpense"))
            val catalogId = requireNotNull(dao.find(sourceId)).catalogId
            dao.save(planEntity(name = "관리비", amount = 90_000, type = "FixedExpense").copy(attributionMonth = YearMonthKey(2026, 6), catalogId = catalogId))

            val keepResult = dao.copyToMonths(
                PlanCopyRequest(
                    sourceMonth = YearMonthKey(2026, 5),
                    type = PlanItemType.FixedExpense,
                    sourceItemId = null,
                    targetMonths = listOf(YearMonthKey(2026, 6), YearMonthKey(2026, 7)),
                    policy = ExistingPlanPolicy.KeepExisting,
                ),
            )
            assertEquals(1, keepResult.skippedConflictCount)
            assertEquals(1, keepResult.insertedItemCount)
            assertEquals(90_000L, dao.loadMonth(2026, 6).single().amountWon)
            assertEquals(null, dao.loadMonth(2026, 7).single().amountWon)

            val overwriteResult = dao.copyToMonths(
                PlanCopyRequest(
                    sourceMonth = YearMonthKey(2026, 5),
                    type = PlanItemType.FixedExpense,
                    sourceItemId = null,
                    targetMonths = listOf(YearMonthKey(2026, 6)),
                    policy = ExistingPlanPolicy.Overwrite,
                ),
            )
            assertEquals(1, overwriteResult.overwrittenItemCount)
            assertEquals(null, dao.loadMonth(2026, 6).single().amountWon)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun copyPlanRejectsDuplicateSourceIdentityWithoutChangingTarget() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-plan-copy-duplicate-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val dao = database.planDao()
            val firstId = dao.save(planEntity(name = "관리비", amount = 100_000, type = "FixedExpense"))
            val catalogId = requireNotNull(dao.find(firstId)).catalogId
            dao.insert(
                MonthlyPlanItemEntity(
                    ledgerId = 1L,
                    catalogId = catalogId,
                    attributionYear = 2026,
                    attributionMonth = 5,
                    amountWon = 120_000,
                    status = PlanItemStatus.Estimated.name,
                    memo = null,
                ),
            )
            dao.save(planEntity(name = "관리비", amount = 90_000, type = "FixedExpense").copy(attributionMonth = YearMonthKey(2026, 6), catalogId = catalogId))

            try {
                dao.copyToMonths(
                    PlanCopyRequest(
                        sourceMonth = YearMonthKey(2026, 5),
                        type = PlanItemType.FixedExpense,
                        sourceItemId = null,
                        targetMonths = listOf(YearMonthKey(2026, 6)),
                        policy = ExistingPlanPolicy.Overwrite,
                    ),
                )
                fail("중복된 원본 항목은 복사 전에 거부되어야 합니다")
            } catch (_: IllegalArgumentException) {
                val target = dao.loadMonth(2026, 6)
                assertEquals(1, target.size)
                assertEquals(90_000L, target.single().amountWon)
            }
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun firstGrandchildClearsParentAmountAndItDoesNotResurfaceAfterDeletion() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-grandchild-derived-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val dao = database.salaryAllocationDao()
            val categoryId = dao.saveCategory(
                SalaryAllocationCategoryEntity(
                    ledgerId = 1L,
                    attributionYear = 2026,
                    attributionMonth = 9,
                    name = "투자",
                    sourceMemberOrder = null,
                    method = SalaryAllocationMethod.ChildTotal.name,
                    amountWon = null,
                    rateBasisPoints = null,
                    memo = null,
                    displayOrder = 0,
                ),
            )
            val originalChild = SalaryAllocationChildEntity(
                categoryId = categoryId,
                attributionYear = 2026,
                attributionMonth = 9,
                name = "ISA",
                amountWon = 900_000,
                memo = null,
                displayOrder = 0,
            )
            val childId = dao.saveChild(originalChild)
            val grandchildId = dao.saveGrandchild(
                SalaryAllocationGrandchildEntity(
                    childId = childId,
                    attributionYear = 2026,
                    attributionMonth = 9,
                    name = "수아 ISA",
                    amountWon = 400_000,
                    memo = null,
                    displayOrder = 0,
                ),
            )

            assertEquals(null, dao.findChild(childId)?.amountWon)
            dao.saveChild(originalChild.copy(id = childId, amountWon = 700_000))
            assertEquals(null, dao.findChild(childId)?.amountWon)

            dao.deleteGrandchild(grandchildId)
            assertEquals(null, dao.findChild(childId)?.amountWon)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun applyFixedCostsRollsBackWhenOneInsertFails() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-fixed-cost-apply-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val dao = database.planDao()

            try {
                dao.applyFixedCosts(
                    targetYear = 2026,
                    targetMonth = 5,
                    inputs = listOf(
                        FixedCostApplyInput(1L, "가스비", 50_000, 0),
                        FixedCostApplyInput(99L, "전기비", 60_000, 0),
                    ),
                    policy = ExistingPlanPolicy.Overwrite,
                )
                fail("유효하지 않은 Ledger FK를 가진 항목이 있어 apply가 실패해야 합니다")
            } catch (_: Throwable) {
                assertEquals(0, dao.loadMonth(2026, 5).size)
            }
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun fixedCostApplyDoesNotAttachNewMonthToArchivedCatalog() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-fixed-cost-archived-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val source = createPlanLocalDataSource(database)
            val archivedId = source.saveCatalog(
                PlanCatalogItem(type = PlanItemType.FixedExpense, classification = "고정비", name = "관리비", ownerMemberOrder = 0),
            )
            source.setCatalogArchived(archivedId, true)

            source.applyFixedCosts(
                YearMonthKey(2026, 5),
                listOf(FixedCostCheckItem(attributionMonth = YearMonthKey(2026, 5), name = "관리비", amountWon = 200_000, payerMemberOrder = 0)),
                ExistingPlanPolicy.Overwrite,
            )

            val saved = source.observeMonth(YearMonthKey(2026, 5)).first().single()
            assertNotEquals(archivedId, saved.catalogId)
            assertEquals(false, source.observeCatalog().first().single { it.id == saved.catalogId }.archived)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun monthlyPlanSaveReusesActiveCatalogAndRejectsArchivedCatalogForNewRows() = runBlocking {
        val tempDirectory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(tempDirectory, "moalog-plan-catalog-save-${System.nanoTime()}.db")
        val database = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 2026, null))
            val source = createPlanLocalDataSource(database)
            fun plan(month: Int, catalogId: Long = 0) = MonthlyPlanItem(
                type = PlanItemType.Income,
                attributionMonth = YearMonthKey(2026, month),
                name = "월급",
                amountWon = 3_000_000,
                category = "급여",
                status = PlanItemStatus.Estimated,
                ownerMemberOrder = 0,
                memo = null,
                catalogId = catalogId,
            )

            val januaryId = source.save(plan(1))
            val january = requireNotNull(source.find(januaryId))
            val februaryId = source.save(plan(2))
            assertEquals(january.catalogId, source.find(februaryId)?.catalogId)
            assertEquals(1, source.observeCatalog().first().size)

            try {
                source.save(plan(1))
                fail("같은 관리 항목은 같은 달에 두 번 연결할 수 없어야 합니다")
            } catch (_: IllegalStateException) {
                assertEquals(1, source.observeMonth(YearMonthKey(2026, 1)).first().size)
            }

            source.setCatalogArchived(january.catalogId, true)
            try {
                source.copyToMonths(
                    PlanCopyRequest(
                        sourceMonth = YearMonthKey(2026, 1),
                        type = PlanItemType.Income,
                        sourceItemId = januaryId,
                        targetMonths = listOf(YearMonthKey(2026, 4)),
                        policy = ExistingPlanPolicy.Overwrite,
                    ),
                )
                fail("보관된 관리 항목은 다른 달에 복사할 수 없어야 합니다")
            } catch (_: IllegalStateException) {
                assertEquals(0, source.observeMonth(YearMonthKey(2026, 4)).first().size)
            }
            try {
                source.save(plan(3, january.catalogId))
                fail("보관된 관리 항목은 새 월별 계획에 연결할 수 없어야 합니다")
            } catch (_: IllegalStateException) {
                assertEquals(0, source.observeMonth(YearMonthKey(2026, 3)).first().size)
            }
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun fixedCostOverwriteCountsAddedAndUpdatedWithoutDoubleCounting() = runBlocking {
        val tempDirectory=targetContext.getDir("instrumentation-db",Context.MODE_PRIVATE)
        val databaseFile=File(tempDirectory,"moalog-fixed-count-${System.nanoTime()}.db")
        val database=buildMoaLogDatabase(Room.databaseBuilder(targetContext,MoaLogDatabase::class.java,databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L,"테스트",2026,null))
            val dao=database.planDao()
            dao.save(planEntity("항목0",1,"FixedExpense"));dao.save(planEntity("항목1",1,"FixedExpense"))
            val incoming=(0 until 9).map{i->FixedCostApplyInput(1L,"항목$i",(i+1L)*10_000L,null)}
            val result=dao.applyFixedCosts(2026,5,incoming,ExistingPlanPolicy.Overwrite)
            assertEquals(7,result.insertedItemCount)
            assertEquals(2,result.overwrittenItemCount)
            assertEquals(9,dao.loadMonth(2026,5).size)
        } finally {database.close();cleanupRoomFiles(databaseFile)}
    }

    @Test
    fun salaryAllocationKeepsMonthsMissingAndZeroIndependent() = runBlocking {
        val tempDirectory=targetContext.getDir("instrumentation-db",Context.MODE_PRIVATE)
        val databaseFile=File(tempDirectory,"moalog-salary-${System.nanoTime()}.db")
        val database=buildMoaLogDatabase(Room.databaseBuilder(targetContext,MoaLogDatabase::class.java,databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L,"테스트",2026,null))
            val source=createSalaryAllocationLocalDataSource(database);val sep=YearMonthKey(2026,9);val oct=YearMonthKey(2026,10)
            source.saveIncomes(listOf(SalaryIncome(sep,0,null),SalaryIncome(sep,1,0)))
            val categoryId=source.saveCategory(SalaryAllocationCategory(attributionMonth=sep,name="투자",method=SalaryAllocationMethod.SalaryRatio,rateBasisPoints=4_000))
            source.saveChild(SalaryAllocationChild(categoryId=categoryId,attributionMonth=sep,name="ISA",amountWon=0))
            val september=source.observe(sep).first();val october=source.observe(oct).first()
            assertEquals(null,september.incomes.first{it.memberOrder==0}.amountWon)
            assertEquals(0L,september.incomes.first{it.memberOrder==1}.amountWon)
            assertEquals(1,september.categories.single().children.size)
            assertEquals(0,october.categories.size)
            assertEquals(0,october.incomes.size)
        } finally { database.close();cleanupRoomFiles(databaseFile) }
    }

    @Test
    fun salaryIncomeReplacementRemovesMembersNoLongerInTheLedgerAndHierarchyRoundTrips() = runBlocking {
        val tempDirectory=targetContext.getDir("instrumentation-db",Context.MODE_PRIVATE)
        val databaseFile=File(tempDirectory,"moalog-salary-v11-${System.nanoTime()}.db")
        val database=buildMoaLogDatabase(Room.databaseBuilder(targetContext,MoaLogDatabase::class.java,databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L,"테스트",2026,null))
            val source=createSalaryAllocationLocalDataSource(database);val month=YearMonthKey(2026,9)
            source.saveIncomes(listOf(SalaryIncome(month,0,1),SalaryIncome(month,1,2)))
            source.saveIncomes(listOf(SalaryIncome(month,7,3),SalaryIncome(month,11,4)))
            val categoryId=source.saveCategory(SalaryAllocationCategory(attributionMonth=month,name="투자",method=SalaryAllocationMethod.ChildTotal,deductedCategoryIds=setOf(8,9)))
            val childId=source.saveChild(SalaryAllocationChild(categoryId=categoryId,attributionMonth=month,name="ISA",amountWon=null))
            source.saveGrandchild(SalaryAllocationGrandchild(childId=childId,attributionMonth=month,name="수아 ISA",amountWon=400_000))
            source.saveGrandchild(SalaryAllocationGrandchild(childId=childId,attributionMonth=month,name="종민 ISA",amountWon=600_000,displayOrder=1))
            val sheet=source.observe(month).first()
            assertEquals(listOf(7,11),sheet.incomes.map{it.memberOrder})
            assertEquals(2,sheet.categories.single().children.single().grandchildren.size)
            assertEquals(1_000_000L,sheet.categories.single().childTotal())
            assertEquals(setOf(8L,9L),sheet.categories.single().deductedCategoryIds)
        } finally {database.close();cleanupRoomFiles(databaseFile)}
    }

    @Test
    fun fixedCostCheckKeepsMonthsMissingAndZeroIndependent() = runBlocking {
        val tempDirectory=targetContext.getDir("instrumentation-db",Context.MODE_PRIVATE)
        val databaseFile=File(tempDirectory,"moalog-fixed-cost-${System.nanoTime()}.db")
        val database=buildMoaLogDatabase(Room.databaseBuilder(targetContext,MoaLogDatabase::class.java,databaseFile.absolutePath))
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L,"테스트",2026,null))
            val source=createFixedCostCheckLocalDataSource(database);val may=YearMonthKey(2026,5);val june=YearMonthKey(2026,6)
            source.save(FixedCostCheckItem(attributionMonth=may,payerMemberOrder=null,name="미입력",amountWon=null))
            source.save(FixedCostCheckItem(attributionMonth=may,payerMemberOrder=0,name="0원",amountWon=0,displayOrder=1))
            val maySheet=source.observe(may).first();val juneSheet=source.observe(june).first()
            assertEquals(null,maySheet.items.first{it.name=="미입력"}.amountWon)
            assertEquals(0L,maySheet.items.first{it.name=="0원"}.amountWon)
            assertEquals(0,juneSheet.items.size)
        } finally { database.close();cleanupRoomFiles(databaseFile) }
    }

    @Test
    fun expenseCrudSurvivesDatabaseReopenAndDeleteIsDurable() = runBlocking {
        val directory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(directory, "moalog-expense-reopen-${System.nanoTime()}.db")
        fun open() = buildMoaLogDatabase(
            Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath),
        )
        var database = open()
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 1900, null))
            var source = createExpenseLocalDataSource(database)
            source.ensureDefaultCategories()
            val month = YearMonthKey(1900, 1)
            val id = source.saveRecord(
                ExpenseRecord(
                    categoryId = DefaultExpenseCategories.first().id,
                    categoryName = DefaultExpenseCategories.first().name,
                    attributionMonth = month,
                    actualDate = LocalDateKey(1900, 1, 1),
                    detail = "처음",
                    amountWon = 10_000,
                    overspent = false,
                ),
            )
            database.close()

            database = open()
            source = createExpenseLocalDataSource(database)
            val reopened = source.findRecord(id)
            assertNotNull(reopened)
            assertEquals(LocalDateKey(1900, 1, 1), reopened!!.actualDate)
            source.saveRecord(reopened.copy(detail = "수정", amountWon = 25_000, overspent = true))
            database.close()

            database = open()
            source = createExpenseLocalDataSource(database)
            val updated = source.observeMonth(month).first().records.single()
            assertEquals("수정", updated.detail)
            assertEquals(25_000L, updated.amountWon)
            assertEquals(true, updated.overspent)
            source.deleteRecord(id)
            database.close()

            database = open()
            source = createExpenseLocalDataSource(database)
            assertEquals(0, source.observeMonth(month).first().records.size)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    @Test
    fun assetCrudAtomicUpdateAndDeletesSurviveDatabaseReopen() = runBlocking {
        val directory = targetContext.getDir("instrumentation-db", Context.MODE_PRIVATE)
        val databaseFile = File(directory, "moalog-asset-reopen-${System.nanoTime()}.db")
        fun open() = buildMoaLogDatabase(Room.databaseBuilder(targetContext, MoaLogDatabase::class.java, databaseFile.absolutePath))
        var database = open()
        try {
            database.ledgerSetupDao().upsertLedger(LedgerEntity(1L, "테스트", 1900, null))
            var source = createAssetLocalDataSource(database)
            val january = YearMonthKey(1900, 1)
            val february = YearMonthKey(1900, 2)
            val id = source.addAssetWithInitial(AssetItem(name = "예금", type = AssetType.Deposit), january, 10_000)
            source.updateAssetWithValuation(
                AssetItem(id, "여행 통장", AssetType.Investment, 1, "장기", AssetKind.PurposeAccount),
                AssetValuation(id, february, 25_000),
            )
            source.saveGrowthRule(AssetGrowthRule(id, january, 3, 10_000, -1_000))
            database.close()

            database = open()
            source = createAssetLocalDataSource(database)
            var portfolio = source.observePortfolio().first()
            assertEquals("여행 통장", portfolio.assets.single().name)
            assertEquals(AssetKind.PurposeAccount, portfolio.assets.single().kind)
            assertEquals(25_000L, portfolio.valuations.first { it.valuationMonth == february }.amountWon)
            assertEquals(1, portfolio.growthRules.size)
            assertEquals(-1_000L, portfolio.growthRules.single().monthlyIncreaseWon)
            source.deleteValuation(id, february)
            source.deleteGrowthRule(id)
            database.close()

            database = open()
            source = createAssetLocalDataSource(database)
            portfolio = source.observePortfolio().first()
            assertEquals(listOf(january), portfolio.valuations.map { it.valuationMonth })
            assertEquals(0, portfolio.growthRules.size)
            source.deleteAsset(id)
            database.close()

            database = open()
            assertEquals(0, createAssetLocalDataSource(database).observePortfolio().first().assets.size)
        } finally {
            database.close()
            cleanupRoomFiles(databaseFile)
        }
    }

    private fun planEntity(name: String, amount: Long?, type: String) = kr.jm.moalog.core.model.MonthlyPlanItem(
        ledgerId = 1L,
        type = PlanItemType.valueOf(type),
        attributionMonth = YearMonthKey(2026, 5),
        name = name,
        amountWon = amount,
        category = if (type == "FixedExpense") "고정비" else "기타",
        status = kr.jm.moalog.core.model.PlanItemStatus.Estimated,
        ownerMemberOrder = null,
        memo = null,
        includePurposeAccount = type == "Savings",
        includeNetSavings = type == "Savings",
    )

    private fun cleanupRoomFiles(databaseFile: File) {
        val dbFileCandidates = listOf(
            databaseFile,
            File("${databaseFile.absolutePath}-journal"),
            File("${databaseFile.absolutePath}-wal"),
            File("${databaseFile.absolutePath}-shm"),
        )
        dbFileCandidates.forEach { file ->
            if (file.exists() && !file.delete()) {
                throw IOException("Failed to delete test database file: $file")
            }
        }
    }
}
