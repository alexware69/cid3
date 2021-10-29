import java.io.Serializable

//This is a utility class to return the certainty and threshold of continuous attributes.
class Certainty(var certainty: Double, var threshold: Double, var thresholdObject : Threshold?, var certaintyClass: Double) : Serializable {
    //This is needed for versioning
    companion object {
        private const val serialVersionUID: Long = 42L
    }
}