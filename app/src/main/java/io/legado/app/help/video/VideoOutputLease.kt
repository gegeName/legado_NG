package io.legado.app.help.video

/** Late callbacks from an outgoing window must never detach the incoming window's output. */
internal class VideoOutputLease<T : Any> {
    var owner: Any? = null
        private set
    var output: T? = null
        private set

    fun attach(owner: Any, output: T) {
        this.owner = owner
        this.output = output
    }

    fun detach(owner: Any, output: T): Boolean {
        if (this.owner !== owner || this.output !== output) return false
        this.output = null
        return true
    }

    fun release(owner: Any): Boolean {
        if (this.owner !== owner) return false
        this.owner = null
        output = null
        return true
    }
}
