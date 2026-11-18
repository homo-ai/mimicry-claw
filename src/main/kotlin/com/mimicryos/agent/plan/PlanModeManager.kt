package com.mimicryos.agent.plan

import com.mimicryos.agent.core.*
import java.util.concurrent.ConcurrentHashMap

class PlanModeManager {
    private val plans = ConcurrentHashMap<String, Plan>()
    private var activePlanId: String? = null

    suspend fun enterPlanMode(prompt: String, cwd: String): PlanModeResult {
        val planId = java.util.UUID.randomUUID().toString()
        val plan = Plan(planId, prompt, PlanStatus.DRAFTING, cwd, mutableListOf(), System.currentTimeMillis())
        plans[planId] = plan; activePlanId = planId
        return PlanModeResult(planId, PlanMode.PLANNING, "Entered plan mode. Explore the codebase and create a plan.")
    }

    fun addStep(planId: String, step: PlanStep) { plans[planId]?.steps?.add(step) }
    fun submitPlan(planId: String): Plan? { val plan = plans[planId] ?: return null; plan.status = PlanStatus.AWAITING_APPROVAL; return plan }
    fun approvePlan(planId: String): PlanModeResult { val plan = plans[planId] ?: return PlanModeResult(planId, PlanMode.ERROR, "Plan not found"); plan.status = PlanStatus.APPROVED; activePlanId = null; return PlanModeResult(planId, PlanMode.EXECUTING, "Plan approved. Ready to execute.") }
    fun rejectPlan(planId: String, reason: String): PlanModeResult { val plan = plans[planId] ?: return PlanModeResult(planId, PlanMode.ERROR, "Plan not found"); plan.status = PlanStatus.REJECTED; plan.rejectionReason = reason; activePlanId = null; return PlanModeResult(planId, PlanMode.ERROR, "Plan rejected: $reason") }
    fun exitPlanMode(): PlanModeResult { activePlanId = null; return PlanModeResult("", PlanMode.DEFAULT, "Exited plan mode") }
    fun getActivePlan(): Plan? = activePlanId?.let { plans[it] }
    fun getPlan(planId: String): Plan? = plans[planId]
    fun isPlanModeActive(): Boolean = activePlanId != null

    data class Plan(val id: String, val prompt: String, var status: PlanStatus, val cwd: String, val steps: MutableList<PlanStep>, val createdAt: Long, var rejectionReason: String? = null)
    data class PlanStep(val description: String, val tool: String? = null, val input: Map<String, Any> = emptyMap(), var status: StepStatus = StepStatus.PENDING)
    data class PlanModeResult(val planId: String, val mode: PlanMode, val message: String)
    enum class PlanMode { DEFAULT, PLANNING, EXECUTING, ERROR }
    enum class PlanStatus { DRAFTING, AWAITING_APPROVAL, APPROVED, REJECTED, EXECUTING, COMPLETED }
    enum class StepStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED }
}
