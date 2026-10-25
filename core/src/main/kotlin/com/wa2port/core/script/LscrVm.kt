package com.wa2port.core.script

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** What the VM is blocked on. */
@Serializable
sealed class WaitState {
    /** `EndMessage`: wait for the player to advance. */
    @Serializable data object Click : WaitState()

    /** `runEX`: yield until the next frame. */
    @Serializable data object Frame : WaitState()

    /** Timed wait until the engine clock reaches [untilMs] (skippable). */
    @Serializable data class Until(val untilMs: Long, val skippable: Boolean = true) : WaitState()

    /** `SetSelect`: wait for a choice. */
    @Serializable data object Choice : WaitState()

    /** `SetMovie`: wait for the host to finish (or skip) the movie. */
    @Serializable data class Movie(val name: String) : WaitState()

    /** Script finished (`GoTitle`, or top-level return). */
    @Serializable data object Ended : WaitState()
}

/** Result of an engine function call. */
sealed class CallOutcome {
    data class Ret(val value: Value) : CallOutcome()
    data class Block(val wait: WaitState) : CallOutcome()
}

@Serializable
data class CallFrame(val script: String, val returnPc: Int)

@Serializable
data class SwitchFrame(val end: Int)

/** Serializable VM state (pc always points at the start of the statement to (re-)execute). */
@Serializable
data class VmSnapshot(
    val script: String,
    val pc: Int,
    val callStack: List<CallFrame>,
    val switchStack: List<SwitchFrame>,
    val globals: List<Value>,
    val locals: Map<Int, Map<Int, Value>>,
    val wait: WaitState?,
)

/** Services the VM needs from the engine. */
interface VmHost {
    fun loadProgram(name: String): LscrProgram?

    /** Opcodes the VM does not implement itself (all engine functions 128..238 and unknowns). */
    fun call(vm: LscrVm, op: Int, spec: FuncSpec?, args: List<Value>, resume: Value?): CallOutcome

    /** JP text of token [token] in the current script (used for `SLoad("1002")` style names). */
    fun tokenString(script: String, token: Int): String

    val nowMs: Long

    fun log(msg: String) {}
}

/**
 * Interpreter for Leaf's `LSCR` bytecode (`.bnr`), executing statement by statement.
 *
 * Expressions are postfix (RPN). Blocking engine calls (`EndMessage`, `runEX`, `Wait`, `SetSelect`,
 * `SetMovie`, ...) suspend the VM *at the start of the current statement*; after [resume] the
 * statement is re-executed and the first blocking call returns the resume value instead of blocking
 * again. This makes every suspension point a clean save point.
 *
 * Operator numbering (lifted from `WA2.exe` 0x451a60): 0 `=`, 1 `+=`, 2 `-=`, 3 `*=`, 4 `/=`,
 * 5 `%=`, 6 `&=`, 7 `|=`, 8 `==`, 9 `<`, 10 `>`, 11 `<=`, 12 `>=`, 13 `&&`, 14 `||`, 15 `!=`,
 * 16 `+`, 17 `-`, 18 `*`, 19 `/`, 20 `%`, 21 `&`, 22 `|`, 23 unary `-`, 24 `!`, 25 `++`, 26 `--`,
 * 27 `[]`, 30 end of statement.
 *
 * Control `(0,sub)`: 0 function begin, 1 return/end, 2 `if (elseAddr, endAddr) cond (0,13)` (false →
 * `elseAddr + 8` skipping the `(0,4)` else marker, or `endAddr` when there is no else), 4 else marker
 * (reached sequentially = end of the then-block → jump to `endAddr`), 5 `for (end, cond, incr, body)`,
 * 7 `switch n {addr, value}×n default expr (0,16)`, 8 case label, 10 break, 12 goto, 13/14/15/16
 * expression terminators.
 */
class LscrVm(private val host: VmHost, val functions: Map<Int, FuncSpec>, globalCount: Int = 64) {
    var program: LscrProgram? = null
        private set
    var pc: Int = 0
        private set
    var wait: WaitState? = null
        private set
    val callStack = ArrayDeque<CallFrame>()
    private val switchStack = ArrayDeque<SwitchFrame>()
    val globals: Array<Value> = Array(globalCount) { Value.ZERO }
    val locals = HashMap<Int, HashMap<Int, Value>>()
    private var resumeValue: Value? = null
    private val stack = ArrayList<Value>(32)

    // A statement may contain several engine calls (e.g. `SEW(0,0) EndMessage(1)`). When one of
    // them blocks, the statement re-executes on resume: calls before the blocking one replay their
    // cached results (no duplicated side effects) and the blocking call receives the resume value.
    private var callIndex = 0
    private var blockedCallIndex = -1
    private var blockedPc = -1
    private val cachedResults = ArrayList<Value>()
    private val stmtResults = ArrayList<Value>()

    /** Unknown operator / opcode counters (diagnostics). */
    val unknownOps = HashMap<String, Int>()
    var statementsExecuted = 0L
        private set

    val scriptName: String get() = program?.name ?: ""
    val isEnded: Boolean get() = wait == WaitState.Ended

    /** Starts [name] at its entry label, discarding call frames. */
    fun start(name: String): Boolean {
        val p = host.loadProgram(name) ?: return false
        program = p
        pc = p.entry
        callStack.clear()
        switchStack.clear()
        wait = null
        resumeValue = null
        return true
    }

    /** Satisfies the current wait; the blocked statement re-executes with [value] as its call result. */
    fun resume(value: Value = Value.ZERO) {
        if (wait == null || wait == WaitState.Ended) return
        wait = null
        resumeValue = value
    }

    /** Forces the VM to stop (e.g. back to title). */
    fun end() {
        wait = WaitState.Ended
    }

    /**
     * Runs until the VM blocks or [maxStatements] statements have executed (a runaway busy loop is
     * treated as a frame yield).
     */
    fun run(maxStatements: Int = 200_000): WaitState? {
        var n = 0
        while (wait == null) {
            if (program == null) {
                wait = WaitState.Ended; break
            }
            step()
            if (++n >= maxStatements) {
                if (wait == null) wait = WaitState.Frame
                break
            }
        }
        return wait
    }

    fun snapshot(): VmSnapshot = VmSnapshot(
        script = scriptName,
        pc = pc,
        callStack = callStack.toList(),
        switchStack = switchStack.toList(),
        globals = globals.toList(),
        locals = locals.mapValues { it.value.toMap() },
        wait = wait?.takeIf { it == WaitState.Ended },
    )

    fun restore(s: VmSnapshot) {
        program = host.loadProgram(s.script)
        pc = s.pc
        callStack.clear(); callStack.addAll(s.callStack)
        switchStack.clear(); switchStack.addAll(s.switchStack)
        s.globals.forEachIndexed { i, v -> if (i < globals.size) globals[i] = v }
        locals.clear()
        s.locals.forEach { (k, m) -> locals[k] = HashMap(m) }
        wait = s.wait
        resumeValue = null
    }

    // ---------------------------------------------------------------------------------------------

    private fun step() {
        val p = program!!
        val w = p.words
        val start = pc
        var i = start / 4
        if (i < 0 || i >= w.size) {
            doReturn(); return
        }
        statementsExecuted++
        stack.clear()
        callIndex = 0
        stmtResults.clear()
        if (blockedPc != start) {
            blockedPc = -1
            blockedCallIndex = -1
        }
        val protectBase = if (isAssignment(w, i)) 1 else 0
        var condIf: IntArray? = null // (elseAddr, endAddr)
        var switchInfo: IntArray? = null
        while (i < w.size) {
            when (w[i]) {
                6 -> {
                    val op = w.getOrElse(i + 1) { 30 }
                    if (op == 30) {
                        pc = (i + 2) * 4; return
                    }
                    applyOp(op)
                    i += 2
                }
                5 -> {
                    val sub = w.getOrElse(i + 1) { 3 }
                    val raw = w.getOrElse(i + 2) { 0 }
                    stack += if (sub == 4) Value.F(java.lang.Float.intBitsToFloat(raw)) else Value.I(raw)
                    i += 3
                }
                3 -> { stack += Value.Text(w[i + 1]); i += 2 }
                1 -> { stack += Value.GRef(w[i + 1]); i += 2 }
                2 -> { stack += Value.LArr(w[i + 1]); i += 2 }
                4 -> {
                    val op = w[i + 1]
                    val nextPc = statementEnd(w, i + 2)
                    when (val r = callFunction(op, protectBase, start, nextPc)) {
                        CallResult.Continue -> i += 2
                        CallResult.Abort -> return
                    }
                }
                0 -> {
                    val sub = w.getOrElse(i + 1) { -1 }
                    when (sub) {
                        0, 8 -> i += 2
                        1 -> { doReturn(); return }
                        2 -> { condIf = intArrayOf(w[i + 2], w[i + 3]); i += 4 }
                        3 -> {
                            val here = i * 4
                            val end = p.elseEnd[here]
                            if (end != null && elseEntry != here) {
                                // Reached sequentially = end of the previous branch → leave the if-chain.
                                pc = end; return
                            }
                            elseEntry = -1
                            condIf = intArrayOf(0, w[i + 2]); i += 3
                        }
                        6 -> { condIf = intArrayOf(0, w[i + 2]); i += 3 }
                        13, 15 -> {
                            val v = truthy(pop())
                            val c = condIf
                            if (c == null || v) {
                                i += 2
                            } else {
                                pc = elseTarget(w, if (c[0] != 0) c[0] else c[1])
                                return
                            }
                        }
                        4 -> {
                            val end = p.elseEnd[i * 4]
                            if (end != null) {
                                pc = end; return
                            }
                            i += 2
                        }
                        5 -> i += 6
                        14 -> {
                            val v = truthy(pop())
                            val f = p.forByCond[start] ?: p.forByCond.values.firstOrNull { (i * 4) in it.cond..it.incr }
                            if (f == null) {
                                unknown("for-without-frame"); i += 2
                            } else {
                                pc = if (v) f.body else f.end
                                return
                            }
                        }
                        7 -> {
                            val n = w[i + 2]
                            switchInfo = w.copyOfRange(i + 2, i + 4 + 2 * n)
                            i += 4 + 2 * n
                        }
                        16 -> {
                            val v = toInt(pop())
                            val sw = switchInfo
                            if (sw == null) {
                                i += 2
                            } else {
                                // sw = [n, (addr, value)×n, default]
                                val n = sw[0]
                                val default = sw.getOrElse(1 + 2 * n) { pc }
                                var target = default
                                for (k in 0 until n) {
                                    if (sw[2 + 2 * k] == v) { target = sw[1 + 2 * k]; break }
                                }
                                switchStack.addLast(SwitchFrame(default))
                                pc = target
                                return
                            }
                        }
                        10 -> {
                            val f = switchStack.removeLastOrNull()
                            if (f != null) {
                                pc = f.end; return
                            }
                            i += 2
                        }
                        12 -> { pc = w[i + 2]; return }
                        else -> { unknown("ctl$sub"); i += LscrProgram.controlLength(w, i) }
                    }
                }
                else -> { unknown("tag${w[i]}"); i += 1 }
            }
        }
        pc = i * 4
    }

    /** Address set when a false condition enters an `else if` header (so it is not taken as fall-through). */
    private var elseEntry = -1

    /** Where a false condition continues: past a `(0,4)` else marker, onto a `(0,3)` elif header, or [t]. */
    private fun elseTarget(w: IntArray, t: Int): Int {
        val wi = t / 4
        if (w.getOrNull(wi) == 0) {
            when (w.getOrNull(wi + 1)) {
                4 -> return t + 8
                3 -> { elseEntry = t; return t }
            }
        }
        return t
    }

    private enum class CallResult { Continue, Abort }

    private fun callFunction(op: Int, protectBase: Int, stmtStart: Int, nextPc: Int): CallResult {
        val spec = functions[op]
        val avail = (stack.size - protectBase).coerceAtLeast(0)
        val argc = spec?.argc ?: avail
        val take = minOf(argc, avail)
        val raw = ArrayList<Value>(argc)
        for (k in stack.size - take until stack.size) raw += stack[k]
        repeat(take) { stack.removeAt(stack.size - 1) }
        val args = raw.map { deref(it) }.toMutableList()
        if (spec != null) {
            for (k in take until argc) args += spec.defaults.getOrNull(k) ?: Value.ZERO
        }
        when (op) {
            0, 1 -> { // SLoad / SCall
                val name = nameOf(args.getOrNull(0))
                val target = host.loadProgram(name)
                if (target == null) {
                    host.log("missing script '$name'")
                    stack += Value.ZERO
                    return CallResult.Continue
                }
                if (op == 1) callStack.addLast(CallFrame(scriptName, nextPc)) else callStack.clear()
                program = target
                pc = target.entry
                switchStack.clear()
                return CallResult.Abort
            }
            2 -> { // call(label)
                val label = toInt(args.getOrNull(0) ?: Value.ZERO)
                val addr = program?.labels?.get(label)
                if (addr == null) {
                    unknown("label$label"); stack += Value.ZERO; return CallResult.Continue
                }
                callStack.addLast(CallFrame(scriptName, nextPc))
                pc = addr
                return CallResult.Abort
            }
            3 -> { stack += Value.ZERO; return CallResult.Continue } // run
            4 -> { host.log("print ${args.firstOrNull()}"); stack += Value.ZERO; return CallResult.Continue }
            5 -> { doReturn(); return CallResult.Abort } // ret
            6 -> { stack += Value.I(toNum(args[0]).toInt()); return CallResult.Continue }
            7 -> { stack += Value.F(toNum(args[0]).toFloat()); return CallResult.Continue }
            8 -> {
                val n = toInt(args[0])
                stack += Value.I(if (n > 0) (Math.random() * n).toInt() else 0)
                return CallResult.Continue
            }
            in 9..17 -> {
                val a = toNum(args[0])
                val b = if (args.size > 1) toNum(args[1]) else 0.0
                // sin/cos/tan take degrees in Leaf scripts (e.g. sin(90*i/n)).
                val r = when (op) {
                    9 -> kotlin.math.sin(Math.toRadians(a))
                    10 -> kotlin.math.cos(Math.toRadians(a))
                    11 -> kotlin.math.tan(Math.toRadians(a))
                    12 -> Math.toDegrees(kotlin.math.asin(a))
                    13 -> Math.toDegrees(kotlin.math.acos(a))
                    14 -> Math.toDegrees(kotlin.math.atan(a))
                    15 -> Math.toDegrees(kotlin.math.atan2(a, b))
                    16 -> Math.pow(a, b)
                    else -> kotlin.math.sqrt(a)
                }
                stack += Value.F(r.toFloat())
                return CallResult.Continue
            }
            18 -> { stack += Value.I(host.nowMs.toInt()); return CallResult.Continue }
        }
        val idx = callIndex++
        val replaying = blockedPc == stmtStart && blockedCallIndex >= 0
        if (replaying && idx < blockedCallIndex && idx < cachedResults.size) {
            stack += cachedResults[idx]
            stmtResults += cachedResults[idx]
            return CallResult.Continue
        }
        val resume = if (replaying && idx == blockedCallIndex) resumeValue else null
        if (replaying && idx >= blockedCallIndex) {
            // The blocked call is being resumed: the statement continues normally from here on.
            blockedPc = -1
            blockedCallIndex = -1
            resumeValue = null
        }
        return when (val out = host.call(this, op, spec, args, resume)) {
            is CallOutcome.Ret -> {
                stack += out.value
                stmtResults += out.value
                CallResult.Continue
            }
            is CallOutcome.Block -> {
                wait = out.wait
                pc = stmtStart
                blockedPc = stmtStart
                blockedCallIndex = idx
                cachedResults.clear()
                cachedResults.addAll(stmtResults)
                CallResult.Abort
            }
        }
    }

    private fun doReturn() {
        switchStack.clear()
        val f = callStack.removeLastOrNull()
        if (f == null) {
            wait = WaitState.Ended
            return
        }
        if (f.script != scriptName) {
            program = host.loadProgram(f.script)
        }
        pc = f.returnPc
    }

    private fun applyOp(op: Int) {
        when (op) {
            27 -> {
                val idx = toInt(deref(pop()))
                val arr = pop()
                stack += when (arr) {
                    is Value.LArr -> Value.LRef(arr.index, idx)
                    is Value.LRef -> Value.LRef(arr.index, arr.elem + idx)
                    else -> { unknown("index-non-array"); Value.ZERO }
                }
            }
            23 -> stack += neg(deref(pop()))
            24 -> stack += Value.of(!truthy(deref(pop())))
            25, 26 -> {
                val ref = pop()
                val old = deref(ref)
                assign(ref, arith(if (op == 25) 16 else 17, old, Value.ONE))
                stack += old
            }
            in 0..7 -> {
                val b = deref(pop())
                val ref = pop()
                val v = when (op) {
                    0 -> b
                    1 -> arith(16, deref(ref), b)
                    2 -> arith(17, deref(ref), b)
                    3 -> arith(18, deref(ref), b)
                    4 -> arith(19, deref(ref), b)
                    5 -> arith(20, deref(ref), b)
                    6 -> arith(21, deref(ref), b)
                    else -> arith(22, deref(ref), b)
                }
                assign(ref, v)
                stack += v
            }
            8, 9, 10, 11, 12, 15 -> {
                val b = deref(pop())
                val a = deref(pop())
                val r = when (op) {
                    8 -> equalsV(a, b)
                    9 -> toNum(a) < toNum(b)
                    10 -> toNum(a) > toNum(b)
                    11 -> toNum(a) <= toNum(b)
                    12 -> toNum(a) >= toNum(b)
                    else -> !equalsV(a, b)
                }
                stack += Value.of(r)
            }
            13, 14 -> {
                val b = truthy(deref(pop()))
                val a = truthy(deref(pop()))
                stack += Value.of(if (op == 13) a && b else a || b)
            }
            in 16..22 -> {
                val b = deref(pop())
                val a = deref(pop())
                stack += arith(op, a, b)
            }
            else -> unknown("op$op")
        }
    }

    private fun arith(op: Int, a: Value, b: Value): Value {
        if (a is Value.I && b is Value.I) {
            val x = a.v
            val y = b.v
            return Value.I(
                when (op) {
                    16 -> x + y
                    17 -> x - y
                    18 -> x * y
                    19 -> if (y == 0) 0 else x / y
                    20 -> if (y == 0) 0 else x % y
                    21 -> x and y
                    else -> x or y
                },
            )
        }
        val x = toNum(a)
        val y = toNum(b)
        return when (op) {
            16 -> Value.F((x + y).toFloat())
            17 -> Value.F((x - y).toFloat())
            18 -> Value.F((x * y).toFloat())
            19 -> Value.F(if (y == 0.0) 0f else (x / y).toFloat())
            20 -> Value.F(if (y == 0.0) 0f else (x % y).toFloat())
            21 -> Value.I(x.toInt() and y.toInt())
            else -> Value.I(x.toInt() or y.toInt())
        }
    }

    private fun neg(v: Value): Value = when (v) {
        is Value.I -> Value.I(-v.v)
        is Value.F -> Value.F(-v.v)
        else -> Value.I(-toInt(v))
    }

    private fun equalsV(a: Value, b: Value): Boolean {
        if (a is Value.I && b is Value.I) return a.v == b.v
        return abs(toNum(a) - toNum(b)) < 1e-6
    }

    fun deref(v: Value): Value = when (v) {
        is Value.GRef -> globals.getOrElse(v.index) { Value.ZERO }
        is Value.LRef -> locals[v.index]?.get(v.elem) ?: Value.ZERO
        is Value.LArr -> locals[v.index]?.get(0) ?: Value.ZERO
        else -> v
    }

    private fun assign(ref: Value, v: Value) {
        when (ref) {
            is Value.GRef -> if (ref.index in globals.indices) globals[ref.index] = v else unknown("gvar${ref.index}")
            is Value.LRef -> locals.getOrPut(ref.index) { HashMap() }[ref.elem] = v
            is Value.LArr -> locals.getOrPut(ref.index) { HashMap() }[0] = v
            else -> unknown("assign-non-ref")
        }
    }

    private fun pop(): Value = if (stack.isEmpty()) {
        unknown("stack-underflow"); Value.ZERO
    } else stack.removeAt(stack.size - 1)

    private fun nameOf(v: Value?): String = when (v) {
        is Value.Text -> host.tokenString(scriptName, v.token)
        is Value.Str -> v.s
        is Value.I -> v.v.toString()
        else -> ""
    }.trim()

    private fun unknown(key: String) {
        unknownOps.merge(key, 1, Int::plus)
    }

    companion object {
        fun toNum(v: Value): Double = when (v) {
            is Value.I -> v.v.toDouble()
            is Value.F -> v.v.toDouble()
            is Value.Str -> v.s.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }

        fun toInt(v: Value): Int = when (v) {
            is Value.I -> v.v
            is Value.F -> v.v.toInt()
            is Value.Str -> v.s.toIntOrNull() ?: 0
            else -> 0
        }

        fun truthy(v: Value): Boolean = when (v) {
            is Value.I -> v.v != 0
            is Value.F -> v.v != 0f
            is Value.Str -> v.s.isNotEmpty()
            is Value.Text -> true
            else -> false
        }

        /** Word index just past the `(6,30)` terminating the statement that contains word [from], as a byte offset. */
        fun statementEnd(w: IntArray, from: Int): Int {
            var i = from
            while (i < w.size) {
                when (w[i]) {
                    6 -> { if (w.getOrElse(i + 1) { 30 } == 30) return (i + 2) * 4; i += 2 }
                    5 -> i += 3
                    1, 2, 3, 4 -> i += 2
                    0 -> i += LscrProgram.controlLength(w, i)
                    else -> i += 1
                }
            }
            return w.size * 4
        }

        /** True when the statement starting at word [from] ends with an assignment operator. */
        fun isAssignment(w: IntArray, from: Int): Boolean {
            var i = from
            var last = -1
            while (i < w.size) {
                when (w[i]) {
                    6 -> {
                        val op = w.getOrElse(i + 1) { 30 }
                        if (op == 30) return last in 0..7
                        last = op; i += 2
                    }
                    5 -> { last = -1; i += 3 }
                    1, 2, 3, 4 -> { last = -1; i += 2 }
                    0 -> { last = -1; i += LscrProgram.controlLength(w, i) }
                    else -> i += 1
                }
            }
            return false
        }
    }
}
