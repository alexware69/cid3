import java.io.Serializable
import java.util.HashMap

//This class will be used to calculate all probabilities in one pass.
class Probabilities(attribute: Int, domainIndToVal: ArrayList<HashMap<Int, String>>, classAtt:Int) : Serializable {
    var prob: DoubleArray = DoubleArray(domainIndToVal[attribute].size)
    var probCAndA: Array<DoubleArray> = Array(domainIndToVal[attribute].size) { DoubleArray(domainIndToVal[classAtt].size) }
    var probCGivenA: Array<DoubleArray> = Array(domainIndToVal[attribute].size) { DoubleArray(domainIndToVal[classAtt].size) }
    companion object {
        private const val serialVersionUID: Long = 42L
    }
}