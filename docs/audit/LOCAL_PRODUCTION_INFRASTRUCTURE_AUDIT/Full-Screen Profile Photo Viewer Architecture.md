# Full-Screen Profile Photo Viewer Architecture

## 1. Executive Summary & Overview
In NEXA, user privacy and identity verification are central. The **Full-Screen Profile Photo Viewer** provides a high-fidelity, immersive experience when inspecting peer profiles, verifying identity badges, and examining avatars.

Crucially, the viewer enforces a **strict discrimination rule**:
- **Real Profile Photos (Priority 1 VEYRA remote/cached images or Priority 2 device contact photos)** are interactive and open the full-screen viewer upon tap.
- **Alphabet Initial Avatars (Priority 3 deterministic letter cards)** are purely symbolic placeholders and **NEVER** trigger or open the full-screen photo viewer.

---

## 2. Visual Layout & UX Specifications 🖼️

```text
┌──────────────────────────────────────────────┐
│                                            ✕ │ ← 48dp Touch Target (28dp Icon, White)
│                                              │
│                                              │
│             ┌──────────────────┐             │
│             │                  │             │
│             │                  │             │
│             │  PROFILE PHOTO   │             │ ← Centered 1:1 (4:4) Square Ratio
│             │   (Strict 1:1)   │             │   minOf(maxWidth, maxHeight)
│             │                  │             │
│             └──────────────────┘             │
│                                              │
│                                              │
└──────────────────────────────────────────────┘
 ▲ Pure Pitch-Black Canvas (#000000) Edge-to-Edge
```

### Layout Properties
1. **Pure Pitch-Black Canvas (`#000000`)**:
   - Background is strictly `#000000` (pitch black), rendering maximum contrast on OLED / AMOLED displays and eliminating distractions.
   - Status bar and navigation bar padding are handled seamlessly via `Modifier.systemBarsPadding()`.
2. **Responsive 1:1 Strict Square Ratio**:
   - Image container enforces `aspectRatio(1f)`.
   - Dimensions are dynamically bounded by `val maxSquareSize = minOf(maxWidth, maxHeight)`.
   - On portrait devices ($W < H$), the photo occupies full screen width without distortion.
   - On landscape, foldable, or tablet devices ($H < W$), the photo occupies full screen height without overflowing off-screen.
3. **Top-Right ✕ Close Button**:
   - `IconButton` with `Icons.Default.Close`, `tint = Color.White`.
   - 48dp touch target, 28dp icon size, positioned at `Alignment.TopEnd` with 16dp margins.
   - System back button / gesture is intercepted via `BackHandler(enabled = true, onBack = onCloseClick)`.

---

## 3. Gesture Mechanics: Zoom & Pan Engine 🔍

The viewer provides smooth hardware-accelerated gestures:

```kotlin
// Clamped zoom between 1.0x and 4.0x
scale = (scale * zoom).coerceIn(1f, 4.0f)

// Clamped panning bounds so image cannot be lost off-screen
if (scale > 1f) {
    val maxBoundX = (maxSquareSize.toPx() * (scale - 1f)) / 2f
    val maxBoundY = (maxSquareSize.toPx() * (scale - 1f)) / 2f
    offsetX = (offsetX + pan.x).coerceIn(-maxBoundX, maxBoundX)
    offsetY = (offsetY + pan.y).coerceIn(-maxBoundY, maxBoundY)
} else {
    offsetX = 0f
    offsetY = 0f
}
```

- **Double-Tap Quick Toggle**: Double-tapping the square photo smoothly toggles between `1.0x` (reset) and `2.5x` (zoom-in).
- **Smooth Recovery**: Returning to `1.0x` zoom instantly snaps `offsetX` and `offsetY` back to `0f`.

---

## 4. Strict Discrimination Logic: Real Photo vs Alphabet Avatar 🛡️

The user requirement explicitly states:
> *"Agar profile photo available nahi hai aur alphabet avatar hai, to alphabet avatar ko full-screen photo viewer ki tarah open nahi karna; sirf actual profile image par viewer open hoga."*

### Architecture Decision:
In [`NexaAvatar.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/common/avatar/NexaAvatar.kt):
1. `onPhotoClick: ((Any) -> Unit)? = null` is introduced.
2. When `resolveAvatar(...)` yields `ResolvedAvatar.NetworkOrLocalUri`:
   - It is an actual photo (Priority 1 remote CDN/local cache or Priority 2 phone contacts photo).
   - If the image loads successfully, the clickable modifier is active and invokes `onPhotoClick(resolved.model)`.
   - If the image fails to load and drops back to `InitialAvatarView`, `isImageLoaded` becomes `false`, disabling clicks.
3. When `resolveAvatar(...)` yields `ResolvedAvatar.InitialAvatar`:
   - `InitialAvatarView` is rendered with no click modifier.
   - Alphabet initial avatars are completely non-interactive with respect to the photo viewer.

### Business Rule Gate:
```kotlin
fun canOpenPhotoViewer(
    resolved: ResolvedAvatar,
    isLoadedSuccessfully: Boolean = true
): Boolean {
    return resolved is ResolvedAvatar.NetworkOrLocalUri && isLoadedSuccessfully
}
```

---

## 5. Universal Integration Points Across NEXA UI 📱

| UI Component | File Location | Interaction & Behavior |
| :--- | :--- | :--- |
| **Full-Screen Viewer Screen** | [`ProfileImageViewerScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/common/viewer/ProfileImageViewerScreen.kt) | Core composable with zoom, pan, black canvas, and ✕ button. |
| **Immersive Dialog Wrapper** | [`ProfileImageViewerScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/common/viewer/ProfileImageViewerScreen.kt) | `ProfileImageViewerDialog` using `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)` for instant 0ms overlay over any screen. |
| **Chat Top Bar** | [`NexaChatTopBar.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/chat/components/NexaChatTopBar.kt) | Tapping peer avatar opens photo viewer (if photo exists). Tapping title/status opens Peer Profile Dialog. |
| **Chat Screen** | [`ChatScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/chat/ChatScreen.kt) | Hosts `activeViewerImageModel` state and presents `ProfileImageViewerDialog`. |
| **Peer Profile Dialog** | [`PeerProfileDialog.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/components/PeerProfileDialog.kt) | Both 36dp header avatar and 72dp hero avatar open full-screen photo viewer on tap. |
| **Conversation Dashboard** | [`MainChatDashboardScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/dashboard/MainChatDashboardScreen.kt) | Tapping conversation avatar opens photo viewer. Tapping card opens chat. |
| **Contacts & Mutual List** | [`ContactListScreen.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/ContactListScreen.kt) | Tapping contact avatar opens photo viewer without interfering with chat launch or profile dialog. |
| **Inbound Request Card** | [`RequestItemCard.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/contacts/components/RequestItemCard.kt) | Inbound connection request sender photo can be inspected full-screen before accepting. |
| **Navigation Graph** | [`AuthNavGraph.kt`](file:///H:/chat_app/app/src/main/java/com/nexa/app/presentation/navigation/AuthNavGraph.kt) | Registered route `Screen.ProfileImageViewer` (`profile_image_viewer?imageUri={imageUri}`) for deep-links and route-based navigation. |

---

## 6. Verification & Automated Test Suite

Automated verification in `ProfilePhotoPriorityUnitTests.kt`:
1. `photoViewer_strictRule_opensOnlyOnRealPhotos_neverOnInitialAvatars`: Confirms Priority 1 and Priority 2 photos are accepted, while Priority 3 Alphabet Avatar is strictly rejected.
2. `photoViewer_loadingFailed_doesNotOpenViewer`: Confirms unloaded/broken photos never open the viewer.
3. Unit test results: **8/8 unit tests PASS**.
4. Production assembly: `gradlew.bat assembleDebug` succeeds.
