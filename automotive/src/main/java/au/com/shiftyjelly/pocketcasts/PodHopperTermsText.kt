package au.com.shiftyjelly.pocketcasts

/**
 * PodHopper: the Terms of Service as shown on the car, which has no web browser to open them in.
 * The wording is copied from the published Terms at podhopper.app/terms and must stay identical
 * to it. When the Terms change, replace this whole file and update the version in PodHopperTerms.
 */
internal object PodHopperTermsText {
    const val TITLE = "PodHopper Terms of Service"
    const val EFFECTIVE_DATE = "July 1, 2026"

    val sections: List<TermsSection> = listOf(
        TermsSection(
            heading = "1. Agreement to these Terms",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "These Terms of Service (the \"Terms\") are an agreement between you and Cover Two Strategies LLC, doing business as Cover Two Games (\"PodHopper\", \"we\", \"us\" or \"our\").", strong = false),
                TermsBlock.Paragraph(lead = null, text = "They cover the PodHopper apps for phones, tablets, watches and vehicles, our website at podhopper.app, and the sync service behind them. Together we call these the \"Service\".", strong = false),
                TermsBlock.Paragraph(lead = "How you accept.", text = "You accept these Terms when you tick the box to create an account, or when you choose to use the Service without an account. If you do not agree, do not use the Service.", strong = false),
                TermsBlock.Paragraph(lead = "Age.", text = "You must be at least 13 years old, or the minimum age of digital consent in your country if that is higher. If you are under 18, a parent or guardian must read and agree to these Terms for you.", strong = false),
                TermsBlock.Paragraph(lead = "Effective date.", text = "July 1, 2026", strong = false),
            ),
        ),
        TermsSection(
            heading = "2. The Service",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "PodHopper is a podcast player. It lets you find, follow and play podcasts, and it keeps your subscriptions, Up Next queue and playback positions in sync across the devices where you sign in.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "PodHopper is for personal entertainment. We do not make, host or control the podcasts you play. Section 5 explains what that means.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "PodHopper is free today. If we add paid features, we will show you the price and any extra terms before you buy anything.", strong = false),
            ),
        ),
        TermsSection(
            heading = "3. Your account",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "You can use PodHopper without an account. You need an account to sync between devices.", strong = false),
                TermsBlock.Paragraph(lead = "Creating an account.", text = "Give us an email address that belongs to you and choose a password. Use a password you do not use anywhere else.", strong = false),
                TermsBlock.Paragraph(lead = "Keeping it secure.", text = "You are responsible for your password and for everything done through your account. Tell us right away at feedback@podhopper.app if you think someone else has access to it.", strong = false),
                TermsBlock.Paragraph(lead = "Sign-in codes for cars and watches.", text = "A car or watch signs in by showing a short code that you enter in the PodHopper app on your phone. Entering a code gives that device access to your account. Only enter a code that is showing on your own car or watch, and never enter a code someone sends you.", strong = false),
                TermsBlock.Paragraph(lead = "Shared, rental and sold vehicles and devices.", text = "A device stays signed in until it is signed out. Before you sell, return or hand over a vehicle or device, sign out of PodHopper on it. Signing out of a car or watch also clears the podcasts, queue and listening history that PodHopper stored on it. If you no longer have the vehicle or device, you can sign it out from the list of signed-in devices in the PodHopper app on your phone. A device signed out that way may keep working for a short time, and it clears its stored data the next time PodHopper runs on it with an internet connection. We are not responsible for what someone else sees or does on a vehicle or device before it is signed out and cleared.", strong = false),
                TermsBlock.Paragraph(lead = "Sync is a convenience, not a backup.", text = "Subscriptions, queues and playback positions can be delayed, overwritten or lost. Keep your own export of your subscriptions if they matter to you.", strong = false),
                TermsBlock.Paragraph(lead = "Deleting your account.", text = "You can delete your account at any time in the app or at covertwogames.com/podhopperdeleteaccount.html. Deleting it removes your account and synced data from our servers. Data stored on your own devices stays there until you clear it or remove the app.", strong = false),
            ),
        ),
        TermsSection(
            heading = "4. Using PodHopper in a vehicle",
            blocks = listOf(
                TermsBlock.Paragraph(lead = "Driving safely is your responsibility, and it always comes first.", text = "This section applies whenever you use PodHopper in or around a vehicle, whether through a vehicle's built-in system, Android Auto, Apple CarPlay, a phone or a watch.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "Your duties as a driver.", strong = true),
                TermsBlock.Bullet(text = "Obey all traffic laws and all laws about using devices and screens while driving.", strong = false),
                TermsBlock.Bullet(text = "Keep your eyes on the road and your hands on the wheel. Do not type, search, browse, sign in, change settings or read text on a screen while the vehicle is moving.", strong = false),
                TermsBlock.Bullet(text = "Set up what you want to hear before you drive. If something needs your attention, pull over where it is safe and legal, or ask a passenger to do it.", strong = false),
                TermsBlock.Bullet(text = "Use steering-wheel buttons and voice commands only when it is safe to do so.", strong = false),
                TermsBlock.Bullet(text = "Keep the volume low enough to hear sirens, horns and what is happening around you.", strong = false),
                TermsBlock.Bullet(text = "Do not rely on PodHopper for anything related to safety, navigation or the operation of your vehicle. It is an entertainment app.", strong = false),
                TermsBlock.Paragraph(lead = "Playback can start on its own.", text = "Audio may start or resume automatically when the vehicle starts, when a device connects, or when PodHopper picks up where another device left off. Be ready for that.", strong = false),
                TermsBlock.Paragraph(lead = "Passengers and other drivers.", text = "If you let someone else use PodHopper in your vehicle, you are responsible for making sure they follow this section.", strong = false),
                TermsBlock.Paragraph(lead = "The vehicle's systems are not ours.", text = "In a vehicle, PodHopper runs on or through an infotainment system made and controlled by the vehicle maker and its suppliers. That system decides much of how PodHopper looks and behaves there, including which controls appear and what is locked while driving. We do not control the vehicle or its systems, and we are not responsible for how they work, fail or respond.", strong = false),
                TermsBlock.Paragraph(lead = "Compatibility.", text = "We do not promise that PodHopper will work with any particular vehicle, model year, system version or region, or that it will keep working after a vehicle software update.", strong = false),
                TermsBlock.Paragraph(lead = "Mobile data.", text = "Streaming uses mobile data, including any data plan built into the vehicle. Any charges are yours.", strong = false),
                TermsBlock.Paragraph(lead = "You accept the risk.", text = "To the fullest extent the law allows, you use PodHopper in or around a vehicle at your own risk, and Sections 11 to 13 apply to that use.", strong = false),
            ),
        ),
        TermsSection(
            heading = "5. Podcasts and other third-party content",
            blocks = listOf(
                TermsBlock.Paragraph(lead = "We do not make or host podcasts.", text = "Podcasts come from their publishers through public feeds. We do not create, host, review, edit or endorse them, and we are not responsible for what they say or contain.", strong = false),
                TermsBlock.Paragraph(lead = "Content may not suit everyone.", text = "Podcasts can include explicit language, mature themes, advertising, opinions and information that is wrong or out of date. PodHopper does not filter content and has no parental controls. You decide what to play, including when children are listening.", strong = false),
                TermsBlock.Paragraph(lead = "Availability can change.", text = "Publishers can change, move or remove podcasts and episodes at any time. We do not promise that any podcast or episode will be or stay available.", strong = false),
                TermsBlock.Paragraph(lead = "Your device connects directly to publishers.", text = "When you play an episode or load artwork, your device connects to the publisher's servers and its hosting or advertising partners. They receive the technical details any connection carries, such as your IP address, and handle them under their own policies.", strong = false),
                TermsBlock.Paragraph(lead = "Search and charts come from third parties.", text = "Search results and top charts are supplied by third-party podcast directories. We do not control what they list or how they rank it.", strong = false),
                TermsBlock.Paragraph(lead = "Links.", text = "The Service may link to websites and services we do not run. We are not responsible for them.", strong = false),
                TermsBlock.Paragraph(lead = "Copyright.", text = "Podcasts and their artwork belong to their owners. PodHopper gives you no rights in them beyond personal listening. If you believe a podcast infringes your rights, contact its publisher or hosting company, since we do not host it. If you believe something in PodHopper itself infringes your rights, write to feedback@podhopper.app.", strong = false),
            ),
        ),
        TermsSection(
            heading = "6. Acceptable use",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "Use the Service only for personal, lawful listening. You agree not to:", strong = false),
                TermsBlock.Bullet(text = "Break any law, or help anyone else break one, while using the Service.", strong = false),
                TermsBlock.Bullet(text = "Access or try to access another person's account or data.", strong = false),
                TermsBlock.Bullet(text = "Probe, scan, overload, attack or disrupt our servers, or get around any security or usage limit.", strong = false),
                TermsBlock.Bullet(text = "Create accounts by automated means, or use the sync service to store anything other than PodHopper data.", strong = false),
                TermsBlock.Bullet(text = "Sell, rent or offer the Service to others as your own, or use it to run a commercial service, without our written permission.", strong = false),
                TermsBlock.Bullet(text = "Use the PodHopper name or logo in a way that confuses people or suggests we endorse you.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "If you find a security problem, please report it to feedback@podhopper.app and give us a reasonable chance to fix it before you tell anyone else.", strong = false),
            ),
        ),
        TermsSection(
            heading = "7. Our rights, your license and open source",
            blocks = listOf(
                TermsBlock.Paragraph(lead = "Your license to use the apps.", text = "While you follow these Terms, we give you a personal, non-exclusive, non-transferable, revocable license to install and use the PodHopper apps on devices and vehicles you own or control.", strong = false),
                TermsBlock.Paragraph(lead = "Open source.", text = "PodHopper's source code is published at github.com/covertwogames under the Mozilla Public License 2.0. Nothing in these Terms takes away rights that license gives you in the source code. These Terms cover what that license does not: the Service we run, your account, and our names and logos. The apps also include open-source components from others, listed in the app with their licenses.", strong = false),
                TermsBlock.Paragraph(lead = "Our names and logos.", text = "\"PodHopper\", the PodHopper frog logo and \"Cover Two Games\" belong to us. The open-source license does not give you any right to use them.", strong = false),
                TermsBlock.Paragraph(lead = "Everything else.", text = "We and our licensors keep all rights in the Service that these Terms and the open-source license do not expressly give you.", strong = false),
                TermsBlock.Paragraph(lead = "Feedback.", text = "If you send us ideas or suggestions, we may use them freely and without owing you anything.", strong = false),
            ),
        ),
        TermsSection(
            heading = "8. Privacy",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "Our Privacy Policy at podhopper.app/privacy explains what information we handle, why, and the choices you have. It applies whenever you use the Service.", strong = false),
            ),
        ),
        TermsSection(
            heading = "9. App stores, vehicle makers and other third parties",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "You may get PodHopper from an app store, such as the Apple App Store, Google Play or a vehicle's own app store. You may run it on a platform such as Android Auto, Apple CarPlay or a vehicle's built-in system. We call the companies behind those stores, platforms and vehicles \"Platform Providers\".", strong = false),
                TermsBlock.Paragraph(lead = "These Terms are between you and us only.", text = "Platform Providers are not parties to these Terms. They are not responsible for the Service or its content, and they have no duty to provide support or maintenance for it.", strong = false),
                TermsBlock.Paragraph(lead = "Their rules still apply to you.", text = "You must follow the terms of the store, platform, vehicle and mobile data service you use. If a store's own terms for its users conflict with these Terms about your use of that store, the store's terms control on that point.", strong = false),
                TermsBlock.Paragraph(lead = "They can rely on these Terms.", text = "Platform Providers and their affiliates are third-party beneficiaries of this section and Sections 11 to 13, and may enforce them against you.", strong = false),
                TermsBlock.Paragraph(lead = "If you got PodHopper from the Apple App Store,", text = "these points also apply:", strong = false),
                TermsBlock.Bullet(text = "These Terms are between you and us, not Apple. We, not Apple, are solely responsible for the app and its content.", strong = false),
                TermsBlock.Bullet(text = "Your license is to use the app on Apple-branded products you own or control, as the Usage Rules in the Apple Media Services Terms and Conditions allow.", strong = false),
                TermsBlock.Bullet(text = "Apple has no obligation to provide maintenance or support for the app.", strong = false),
                TermsBlock.Bullet(text = "If the app fails to meet a warranty that applies, you may tell Apple, and Apple will refund the purchase price, if any. To the fullest extent the law allows, Apple has no other warranty obligation for the app.", strong = false),
                TermsBlock.Bullet(text = "We, not Apple, are responsible for handling any claim about the app, including product liability claims, claims that it fails to meet a legal or regulatory requirement, and consumer protection or privacy claims.", strong = false),
                TermsBlock.Bullet(text = "If someone claims the app infringes their intellectual property, we, not Apple, are responsible for investigating, defending, settling and resolving that claim.", strong = false),
                TermsBlock.Bullet(text = "You confirm that you are not in a country under a United States government embargo or designated by it as supporting terrorism, and that you are not on any United States government list of prohibited or restricted parties.", strong = false),
                TermsBlock.Bullet(text = "Apple and its subsidiaries are third-party beneficiaries of these Terms and may enforce them against you.", strong = false),
            ),
        ),
        TermsSection(
            heading = "10. Changes to the Service, suspension and ending",
            blocks = listOf(
                TermsBlock.Paragraph(lead = "We can change the Service.", text = "We may add, change or remove features, or stop offering the Service or any version of it, at any time. If we plan to shut down sync, we will try to give you notice so you can export your subscriptions first.", strong = false),
                TermsBlock.Paragraph(lead = "Updates and support.", text = "We intend to keep improving PodHopper, but we are not obliged to provide updates, fixes or support.", strong = false),
                TermsBlock.Paragraph(lead = "You can stop at any time.", text = "Delete your account and remove the app.", strong = false),
                TermsBlock.Paragraph(lead = "We can suspend or end your access.", text = "We may do this if you break these Terms, if it is needed to protect the Service or other people, or if the law requires it.", strong = false),
                TermsBlock.Paragraph(lead = "What survives.", text = "Sections 4, 5, 7 and 9 to 16 continue to apply after these Terms end.", strong = false),
            ),
        ),
        TermsSection(
            heading = "11. Disclaimer of warranties",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "The Service is provided \"as is\" and \"as available\", and you use it at your own risk. To the fullest extent the law allows, we and our suppliers disclaim all warranties, whether express, implied or statutory. That includes any warranty of merchantability, fitness for a particular purpose, title, non-infringement, quiet enjoyment and accuracy.", strong = true),
                TermsBlock.Paragraph(lead = null, text = "In particular, we do not promise that:", strong = true),
                TermsBlock.Bullet(text = "the Service will be uninterrupted, secure, timely or free of errors;", strong = true),
                TermsBlock.Bullet(text = "sync will never delay, overwrite or lose your subscriptions, queue or playback positions;", strong = true),
                TermsBlock.Bullet(text = "any podcast, episode, search result or chart will be available, accurate or suitable;", strong = true),
                TermsBlock.Bullet(text = "the Service will work with any particular vehicle, device or system version; or", strong = true),
                TermsBlock.Bullet(text = "any defect will be corrected.", strong = true),
                TermsBlock.Paragraph(lead = null, text = "Some places do not allow some of these disclaimers, so some of them may not apply to you. You may also have consumer rights that cannot be waived. Nothing in these Terms limits those rights.", strong = false),
            ),
        ),
        TermsSection(
            heading = "12. Limitation of liability",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "To the fullest extent the law allows, we will not be liable for any of the following, however it arises and even if we were told it was possible:", strong = true),
                TermsBlock.Bullet(text = "indirect, incidental, special, consequential, exemplary or punitive damages;", strong = true),
                TermsBlock.Bullet(text = "lost profits, revenue, data, goodwill or use;", strong = true),
                TermsBlock.Bullet(text = "any accident, injury, death, property damage, fine or penalty connected with using the Service in or around a vehicle;", strong = true),
                TermsBlock.Bullet(text = "the content of any podcast, or anything a publisher, directory or other third party does or fails to do; or", strong = true),
                TermsBlock.Bullet(text = "the behavior or failure of any vehicle, vehicle system, device, network or Platform Provider.", strong = true),
                TermsBlock.Paragraph(lead = null, text = "Our total liability for all claims connected with the Service or these Terms is limited to the greater of (a) the amount you paid us for the Service in the 12 months before the claim arose and (b) fifty U.S. dollars (US\$50).", strong = true),
                TermsBlock.Paragraph(lead = null, text = "\"We\" in this section and in Section 11 includes Cover Two Strategies LLC and its owners, officers, employees, contractors and suppliers.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "These limits apply to every kind of legal claim, including contract, negligence and other torts, and strict liability. They apply even if a remedy does not fully compensate you. The Service is free, and these limits are part of the basis on which we offer it.", strong = false),
                TermsBlock.Paragraph(lead = "What these limits do not do.", text = "They do not limit liability that the law does not allow us to limit. Depending on where you live, that can include liability for death or personal injury caused by our negligence, for fraud, or for intentional misconduct. If you are a consumer, they do not take away rights the law of your country guarantees you.", strong = false),
            ),
        ),
        TermsSection(
            heading = "13. Your responsibility to us",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "To the fullest extent the law allows, you agree to defend us and pay for any claim, loss, damage, fine or cost, including reasonable attorneys' fees, that a third party brings against us because of:", strong = false),
                TermsBlock.Bullet(text = "your breach of these Terms;", strong = false),
                TermsBlock.Bullet(text = "your breach of any law or of someone else's rights; or", strong = false),
                TermsBlock.Bullet(text = "your use of the Service while driving or operating a vehicle.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "\"Us\" here means Cover Two Strategies LLC and its owners, officers, employees, contractors and suppliers, and the Platform Providers.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "This does not apply to the extent a claim was caused by our own breach of these Terms or our own wrongful conduct. We will tell you promptly about any such claim, and we may choose to run the defense ourselves.", strong = false),
            ),
        ),
        TermsSection(
            heading = "14. Governing law and disputes",
            blocks = listOf(
                TermsBlock.Paragraph(lead = "Talk to us first.", text = "If you have a problem with the Service, write to feedback@podhopper.app. We will each try in good faith to settle it within 30 days before either of us starts a legal case.", strong = false),
                TermsBlock.Paragraph(lead = "Governing law.", text = "The laws of the State of Michigan, USA govern these Terms and any dispute connected with the Service, without regard to its conflict-of-law rules.", strong = false),
                TermsBlock.Paragraph(lead = "Courts.", text = "Any legal case must be brought only in the state or federal courts located in Ingham County, Michigan. You and we both agree to the jurisdiction of those courts.", strong = false),
                TermsBlock.Paragraph(lead = "No class actions and no jury.", text = "To the fullest extent the law allows, you and we may bring claims only as individuals. Neither of us may bring or join a class, collective or representative action, and each of us gives up the right to a jury trial.", strong = false),
                TermsBlock.Paragraph(lead = "Time limit.", text = "To the fullest extent the law allows, any claim connected with the Service or these Terms must be filed within one year after it arises. After that, it is permanently barred.", strong = false),
                TermsBlock.Paragraph(lead = "Consumers outside the United States.", text = "If you are a consumer and the law of your country gives you the right to its protections or to its courts, nothing in this section takes that away.", strong = false),
            ),
        ),
        TermsSection(
            heading = "15. Changes to these Terms",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "We may update these Terms. The current version and its effective date are always at podhopper.app/terms.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "If a change is important, we will tell you in the app or by email before it takes effect, and we may ask you to accept the new version. If you do not agree to a change, stop using the Service and delete your account.", strong = false),
                TermsBlock.Paragraph(lead = null, text = "A change does not apply to a dispute that arose before the change took effect.", strong = false),
            ),
        ),
        TermsSection(
            heading = "16. General terms",
            blocks = listOf(
                TermsBlock.Paragraph(lead = "Whole agreement.", text = "These Terms, the Privacy Policy and any extra terms we show you for a specific feature are the whole agreement between you and us about the Service.", strong = false),
                TermsBlock.Paragraph(lead = "If part is unenforceable.", text = "If a court finds part of these Terms unenforceable, that part is limited as little as needed and the rest stays in force.", strong = false),
                TermsBlock.Paragraph(lead = "No waiver.", text = "If we do not enforce a term, we have not given it up.", strong = false),
                TermsBlock.Paragraph(lead = "Transfer.", text = "You may not transfer these Terms or your account to anyone else. We may transfer these Terms to a company that takes over the Service.", strong = false),
                TermsBlock.Paragraph(lead = "Notices.", text = "We may send you notices in the app or to your account email address. Send notices to us at the addresses in Section 17.", strong = false),
                TermsBlock.Paragraph(lead = "Events outside our control.", text = "We are not responsible for a failure or delay caused by something we cannot reasonably control, such as an outage at a hosting or network provider.", strong = false),
                TermsBlock.Paragraph(lead = "Trade rules.", text = "You may not use the Service where United States law forbids it, or if you are on a United States government list of prohibited or restricted parties.", strong = false),
                TermsBlock.Paragraph(lead = "Language.", text = "These Terms are written in English. If we provide a translation and it differs, the English version controls.", strong = false),
            ),
        ),
        TermsSection(
            heading = "17. Contact us",
            blocks = listOf(
                TermsBlock.Paragraph(lead = null, text = "Cover Two Strategies LLC, doing business as Cover Two Games", strong = false),
                TermsBlock.Paragraph(lead = null, text = "230 N. Washington Sq., Suite 101, Lansing, MI 48933", strong = false),
                TermsBlock.Paragraph(lead = null, text = "Email: feedback@podhopper.app", strong = false),
                TermsBlock.Paragraph(lead = null, text = "Use the same address for questions about these Terms, complaints, legal notices and security reports.", strong = false),
            ),
        ),
    )
}

internal data class TermsSection(val heading: String, val blocks: List<TermsBlock>)

internal sealed interface TermsBlock {
    /** [lead] is a bold opening phrase. [strong] means the whole paragraph is shown in bold. */
    data class Paragraph(val lead: String?, val text: String, val strong: Boolean) : TermsBlock

    data class Bullet(val text: String, val strong: Boolean) : TermsBlock
}
