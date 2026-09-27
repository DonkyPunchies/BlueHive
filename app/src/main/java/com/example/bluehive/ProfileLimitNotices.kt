package com.example.bluehive

import com.example.bluehive.utilities.NoticeContent
import com.example.bluehive.utilities.NoticeTone

// ─────────────────────────────────────────────────────────────────────────────
// ProfileLimitNotices.kt
//
// The words the app uses when a profile cannot be created, in one place.
//
// TWO SCREENS REACH THE SAME DEAD END BY DIFFERENT ROUTES, and they have to say
// the same thing when they get there. ProfileScreen learns the limit up front
// from GET /profiles/quota and refuses the press; ProfileCreationActivity
// learns it from a 409 after the form was submitted. Written twice, the two
// wordings drift, and the one a user sees becomes an accident of which path
// they took.
//
// Neither number is hardcoded here. Both arrive from the server — `limit` in
// the 409 body, `device_limit` / `account_limit` in the quota response — so
// moving BH_MAX_PROFILES_PER_DEVICE moves the sentence with it.
// ─────────────────────────────────────────────────────────────────────────────

/** What was tried on another account's profile and refused. */
internal enum class NotYours { Delete, Picture }

/**
 * Tried to delete, or change the picture of, a profile belonging to ANOTHER
 * ACCOUNT on the subscription -- from Manage Profiles, which lists the whole
 * household.
 *
 * Watching under it is fine -- the household shares a licence, so every profile
 * on it is enterable from every TV on it -- and so is renaming it, since an
 * unsuitable name is on everyone's screen. The rest belongs to the account that
 * made it, and the server refuses it (a 404 for a delete, 403 profile_not_yours
 * for a picture); these say why before the attempt is made.
 *
 * THE ACCOUNT, NOT THE TV. An owner's profiles can be managed from any of the
 * owner's four TVs; it is the account that owns them. (The first draft of this
 * said "the TV that made this profile", which was wrong.)
 *
 * Describes the owner as the SUBSCRIPTION rather than naming the other account:
 * that is the relationship the person understands, and BlueHive deliberately
 * cannot resolve a peer to a human identity anyway -- it holds a one-way
 * pseudonym, not a name.
 */
internal fun profileNotYoursNotice(action: NotYours): NoticeContent = NoticeContent(
    title   = when (action) {
        NotYours.Delete  -> "Only the account that made this profile can delete it."
        NotYours.Picture -> "Only the account that made this profile can change its picture."
    },
    message = "Everyone on your subscription can watch under any profile on it, " +
              "and anyone can fix its name. " +
              when (action) {
                  NotYours.Delete  -> "Deleting it stays with the account that created it."
                  NotYours.Picture -> "Its picture stays with the account that created it."
              },
    tone    = NoticeTone.Info,
)

/** Which ceiling was reached. They have opposite remedies, hence the split. */
internal enum class ProfileLimit { Device, Account }

/**
 * @param limit the number the server enforced, or 0 when it did not say — in
 *              which case the copy simply avoids quoting a figure rather than
 *              guessing one.
 */
internal fun profileLimitNotice(limit: ProfileLimit, count: Int): NoticeContent = when (limit) {

    ProfileLimit.Device -> NoticeContent(
        title = if (count > 0) "This TV has already made all $count of its profiles."
                else           "This TV has already made all of its profiles.",
        message = "Every television paired to this account can create " +
                  (if (count > 0) "$count profiles" else "a few profiles") +
                  " of its own, and this one has used all of them. Delete one of them " +
                  "under Manage Profiles, or create the profile on another paired TV — " +
                  "profiles are shared across the account, so it will still appear here.",
    )

    ProfileLimit.Account -> NoticeContent(
        title = if (count > 0) "This account is full at $count profiles."
                else           "This account has no free profile slots.",
        message = "That ceiling counts every profile on the account at once, across all " +
                  "of your televisions — not just the ones made on this TV. Delete one " +
                  "under Manage Profiles to make room.",
    )
}
