package com.rhshourav.tenchar.store

/** Everything the app remembers: every chat, and the profile new chats start with. Encrypted at rest by [Vault]. */
class AppState(
    val conversations: MutableList<Conversation> = ArrayList(),
    /** What a new chat starts with as "you". Shared with the other person once the chat is verified. */
    var defaultProfile: Profile = Profile.EMPTY,
) {
    fun wipe() {
        for (c in conversations) c.wipe()
    }

    fun find(localId: String): Conversation? = conversations.firstOrNull { it.localId == localId }

    companion object {
        const val MAX_CHATS = 20
    }
}