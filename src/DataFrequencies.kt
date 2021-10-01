import Cid3.DataPoint
import java.io.Serializable
import java.util.*

/**
 *
 * @author alex
 */
class DataFrequencies(var data: ArrayList<DataPoint>, var frequencyClasses: IntArray) : Serializable{
    companion object {
        private const val serialVersionUID: Long = 42L
    }
}