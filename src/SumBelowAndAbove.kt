import java.io.Serializable

/**
 *
 * @author alex
 */
class SumBelowAndAbove(var below: Int, var above: Int) : Serializable{
    companion object {
        private const val serialVersionUID: Long = 42L
    }
}