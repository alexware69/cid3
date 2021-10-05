import java.io.Serializable
import java.util.ArrayList

/* The class to represent a node in the decomposition tree.
    */
class TreeNode : Serializable {
    var certaintyUsedToDecompose = 0.0
    // The set of data points if this is a leaf node
    @Transient
    var data: ArrayList<DataPoint> = ArrayList()
    //This is for saving time when calculating most common class
    lateinit var frequencyClasses: IntArray
    // If this is not a leaf node, the attribute that is used to divide the set of data points
    var decompositionAttribute = 0
    // the attribute-value that is used to divide the parent node
    var decompositionValue = 0
    var decompositionValueContinuous = ""
    var decompositionValueContinuousSymbol = ""
    var thresholdContinuous = 0.0
    // If this is not a leaf node, references to the children nodes
    var children: ArrayList<TreeNode>? = null
    // The parent to this node.  The root has its parent == null
    var parent: TreeNode? = null

    //This is needed for versioning
    companion object {
        private const val serialVersionUID: Long = 42L
    }
}