class ConsoleHelper {
    private var lastLine = ""
    private fun print(line: String) {
        //clear the last line if longer
        if (lastLine.length > line.length) {
            var temp = ""
            for (i in lastLine.indices) {
                temp += " "
            }
            if (temp.length > 1) kotlin.io.print("\r" + temp)
        }
        kotlin.io.print("\r" + line)
        lastLine = line
    }

    private var anim: Int = 0
    fun animate() {
        when (anim) {
            1 -> print("[ \\ ] ")
            2 -> print("[ | ] ")
            3 -> print("[ / ] ")
            else -> {
                anim = 0
                print("[ ─ ] ")
            }
        }
        anim++
    }
}