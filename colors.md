Here is the exact color palette and gradient breakdown for the dark version of the **TC (TenChar)** logo. These are perfect for setting up your Material 3 dark theme in Jetpack Compose!

### 1. The "T" Gradient (Mint to Lavender)

This is a smooth, glowing transition from top-left to bottom-right.

* **Mint Green (Top/Left):** `#84F5B7`
* **Soft Lavender (Top/Right & Middle):** `#B491FE`
* **CSS/Compose Linear Gradient:**
`LinearGradient(colors = listOf(Color(0xFF84F5B7), Color(0xFFB491FE)))`

### 2. The "C" Gradient (Peach to Indigo)

This gradient curves around the "C", starting bright at the top right and dropping into a deep, rich shadow.

* **Soft Peach/Amber (Top):** `#FFB385`
* **Deep Indigo/Violet (Bottom Curve):** `#5A33D3`
* **CSS/Compose Linear Gradient:**
`LinearGradient(colors = listOf(Color(0xFFFFB385), Color(0xFF5A33D3)))`

### 3. The Dark Background & Frost

To get that deep, space-like dark mode feel without using pure black (`#000000`):

* **Deep Background Space (Base):** `#13141C` (Very dark blue-grey)
* **Dark Purple Blob/Shadow Accent:** `#1E1B38`
* **Glassmorphism/Frosted Plate:** White at 5% to 10% opacity (`rgba(255, 255, 255, 0.08)`) with a strong background blur, paired with a subtle white stroke (`rgba(255, 255, 255, 0.15)`) for the edge highlight.

### 4. Accent Glowing Dots

* **Mint Glow:** `#84F5B7`
* **Peach Glow:** `#FFB385`
* **Lavender Glow:** `#D2B8FF`
