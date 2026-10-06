package com.example.ava.wakelearn

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Fits a [LinearVerifierHead] from the household's own labelled wakes.
 *
 * Logistic regression on standardized features, class-balanced, with an L2 penalty that
 * pulls the solution toward the prior head (the factory calibration when one is bundled,
 * zero otherwise) instead of toward zero — so a handful of personal samples refines the
 * factory behavior rather than replacing it, and a wake word without any factory head
 * gets a plain regularized fit. Full-batch Adam; a few hundred samples of 1.5–2.5 k
 * dims fit in well under a second on a phone.
 *
 * A candidate head is published only if k-fold cross-validation shows it keeps the
 * user's genuine wakes (≥ [MIN_POSITIVE_RETENTION] of held-out positives pass at the
 * veto threshold) while vetoing at least as many held-out false wakes as the prior did.
 * Otherwise [train] returns null and the caller keeps whatever head it has.
 */
object OnDeviceVerifierTrainer {
    class Report(
        val positives: Int,
        val negatives: Int,
        val heldOutPositivePass: Float,
        val heldOutNegativeVeto: Float,
        val priorHeldOutNegativeVeto: Float,
    ) {
        override fun toString(): String =
            "pos=$positives neg=$negatives cvPosPass=${"%.2f".format(heldOutPositivePass)} " +
                "cvNegVeto=${"%.2f".format(heldOutNegativeVeto)} " +
                "priorNegVeto=${"%.2f".format(priorHeldOutNegativeVeto)}"
    }

    class Result(val head: LinearVerifierHead, val report: Report)

    const val MIN_POSITIVES = 6
    const val MIN_NEGATIVES = 3
    const val MIN_POSITIVE_RETENTION = 0.90f
    private const val FOLDS = 4
    private const val ITERATIONS = 300
    private const val LEARNING_RATE = 0.05
    /** Mirrors the factory fit (sklearn C=0.02): mean loss + ||w - w0||² / (2·C·n). */
    private const val C = 0.02

    fun train(
        samples: List<WakeLearnStore.Sample>,
        prior: LinearVerifierHead?,
        dims: Int,
        vetoThreshold: Float,
        minPositiveRetention: Float = MIN_POSITIVE_RETENTION,
    ): Result? {
        val data = samples.filter { it.x.size == dims }
        if (prior != null && prior.dims != dims) return null
        val report = crossValidate(data, prior, dims, vetoThreshold) ?: return null
        if (report.heldOutPositivePass < minPositiveRetention) return null
        if (report.heldOutNegativeVeto < report.priorHeldOutNegativeVeto) return null
        return Result(fit(data, prior, dims), report)
    }

    /**
     * K-fold estimate of what a head fit on [samples] would do to held-out wakes. Null
     * below the minimum evidence. Also used to explain a refused fit to the user.
     */
    fun crossValidate(
        samples: List<WakeLearnStore.Sample>,
        prior: LinearVerifierHead?,
        dims: Int,
        vetoThreshold: Float,
    ): Report? {
        val data = samples.filter { it.x.size == dims }
        val pos = data.filter { it.positive }
        val neg = data.filterNot { it.positive }
        if (pos.size < MIN_POSITIVES || neg.size < MIN_NEGATIVES) return null
        val folds = assignFolds(pos.size, neg.size)
        var heldPos = 0; var heldPosPass = 0
        var heldNeg = 0; var heldNegVeto = 0; var priorNegVeto = 0
        for (k in 0 until FOLDS) {
            val trainSet = ArrayList<WakeLearnStore.Sample>()
            val test = ArrayList<WakeLearnStore.Sample>()
            pos.forEachIndexed { i, s -> if (folds.first[i] == k) test.add(s) else trainSet.add(s) }
            neg.forEachIndexed { i, s -> if (folds.second[i] == k) test.add(s) else trainSet.add(s) }
            if (trainSet.none { it.positive } || trainSet.none { !it.positive } || test.isEmpty()) continue
            val head = fit(trainSet, prior, dims)
            for (s in test) {
                val pass = head.score(s.x) >= vetoThreshold
                if (s.positive) {
                    heldPos++
                    if (pass) heldPosPass++
                } else {
                    heldNeg++
                    if (!pass) heldNegVeto++
                    if (prior != null && prior.score(s.x) < vetoThreshold) priorNegVeto++
                }
            }
        }
        if (heldPos == 0 || heldNeg == 0) return null
        return Report(
            pos.size, neg.size,
            heldPosPass.toFloat() / heldPos,
            heldNegVeto.toFloat() / heldNeg,
            priorNegVeto.toFloat() / heldNeg,
        )
    }

    /** Deterministic round-robin fold ids so both classes appear in every fold. */
    private fun assignFolds(nPos: Int, nNeg: Int): Pair<IntArray, IntArray> =
        IntArray(nPos) { it % FOLDS } to IntArray(nNeg) { it % FOLDS }

    private fun fit(samples: List<WakeLearnStore.Sample>, prior: LinearVerifierHead?, dims: Int): LinearVerifierHead {
        val n = samples.size
        val mu = DoubleArray(dims)
        val sd = DoubleArray(dims)
        for (s in samples) for (d in 0 until dims) mu[d] += s.x[d]
        for (d in 0 until dims) mu[d] /= n
        for (s in samples) for (d in 0 until dims) { val v = s.x[d] - mu[d]; sd[d] += v * v }
        for (d in 0 until dims) sd[d] = sqrt(sd[d] / n) + 1e-3

        // Standardized copies and the prior expressed in the standardized space.
        val xs = Array(n) { i -> DoubleArray(dims) { d -> (samples[i].x[d] - mu[d]) / sd[d] } }
        val w0 = DoubleArray(dims)
        var b0 = 0.0
        if (prior != null) {
            for (d in 0 until dims) w0[d] = prior.weights[d] * sd[d]
            b0 = prior.bias.toDouble()
            for (d in 0 until dims) b0 += prior.weights[d] * mu[d]
        }

        val nPos = samples.count { it.positive }
        val nNeg = n - nPos
        val wPos = 0.5 * n / nPos
        val wNeg = 0.5 * n / nNeg
        val lambda = 1.0 / (2.0 * C * n)

        val w = w0.copyOf()
        var b = b0
        val mW = DoubleArray(dims); val vW = DoubleArray(dims)
        var mB = 0.0; var vB = 0.0
        val gW = DoubleArray(dims)
        val beta1 = 0.9; val beta2 = 0.999; val eps = 1e-8
        for (t in 1..ITERATIONS) {
            gW.fill(0.0)
            var gB = 0.0
            for (i in 0 until n) {
                val x = xs[i]
                var z = b
                for (d in 0 until dims) z += w[d] * x[d]
                val p = 1.0 / (1.0 + exp(-z))
                val y = if (samples[i].positive) 1.0 else 0.0
                val g = (p - y) * (if (samples[i].positive) wPos else wNeg) / n
                for (d in 0 until dims) gW[d] += g * x[d]
                gB += g
            }
            for (d in 0 until dims) gW[d] += 2.0 * lambda * (w[d] - w0[d])
            val lrT = LEARNING_RATE * sqrt(1 - Math.pow(beta2, t.toDouble())) / (1 - Math.pow(beta1, t.toDouble()))
            for (d in 0 until dims) {
                mW[d] = beta1 * mW[d] + (1 - beta1) * gW[d]
                vW[d] = beta2 * vW[d] + (1 - beta2) * gW[d] * gW[d]
                w[d] -= lrT * mW[d] / (sqrt(vW[d]) + eps)
            }
            mB = beta1 * mB + (1 - beta1) * gB
            vB = beta2 * vB + (1 - beta2) * gB * gB
            b -= lrT * mB / (sqrt(vB) + eps)
        }

        // Fold standardization back into raw-space weights.
        val weights = FloatArray(dims)
        var bias = b
        for (d in 0 until dims) {
            weights[d] = (w[d] / sd[d]).toFloat()
            bias -= w[d] * mu[d] / sd[d]
        }
        return LinearVerifierHead(weights, bias.toFloat())
    }

    /** Cross-entropy of a head on labelled samples (diagnostics / tests). */
    fun loss(head: LinearVerifierHead, samples: List<WakeLearnStore.Sample>): Double {
        var total = 0.0
        for (s in samples) {
            val p = head.score(s.x).toDouble().coerceIn(1e-6, 1 - 1e-6)
            total += if (s.positive) -ln(p) else -ln(1 - p)
        }
        return total / samples.size.coerceAtLeast(1)
    }
}
