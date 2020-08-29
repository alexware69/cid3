import java.io.Serializable

/**
 *
 * @author alex
 */
class Threshold(var value: Double, var sumsClassesAndAttribute: Array<SumBelowAndAbove?>) : Serializable {
    var sumABelow = 0
    var sumAAbove = 0
}