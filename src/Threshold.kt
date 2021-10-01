import java.io.Serializable

/**
 *
 * @author alex
 */
class Threshold(var value: Double, var sumsClassesAndAttribute: Array<SumBelowAndAbove?>) : Serializable {
    var sumABelow = 0
    var sumAAbove = 0

    companion object {
        private const val serialVersionUID: Long = 42L
    }
}