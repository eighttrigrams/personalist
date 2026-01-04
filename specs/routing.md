# Routing

## URL Structure

- `/` - Home page (no persona selected)
- `/:persona-id` - Persona splash page showing recent identities
- `/:persona-id/:identity-id` - View a specific identity
- `/:persona-id/:identity-id?edit=true` - Edit mode for an identity
- `/:persona-id/:identity-id?time=<ISO-timestamp>` - View identity at a specific point in time

## Navigation Behavior

### Home Page (`/`)

When no persona is selected, show a prompt to select a persona.

### Persona Splash Page (`/:persona-id`)

When a valid persona ID is in the URL:
- Display the persona's name and ID
- Show the 5 most recently modified identities
- Provide a "more" button to paginate through identities (replaces current 5 with next 5)

When an invalid persona ID is in the URL:
- Display "Persona not found" with the invalid ID
- Provide a "Go Home" button that navigates to `/`

### Identity View (`/:persona-id/:identity-id`)

When both persona and identity are valid:
- Display the identity content, history slider, and relations

When persona is valid but identity is invalid:
- Display "Identity not found" with the invalid ID
- Provide a "Back to Persona" button that navigates to `/:persona-id`

## Header Navigation

### Banner ("Personalist")

Clicking the banner should:
- If logged in: navigate to the logged-in user's splash page
- If viewing a persona (not logged in): navigate to that persona's splash page
- If neither: navigate to `/`

### "Logged in: X" (when authenticated)

Clicking should navigate to the logged-in user's splash page.

### "Persona: X" (when viewing a persona without being logged in)

Clicking should navigate to that persona's splash page.

### All header navigation actions should:
- Clear any "not found" error states
- Reset the identity selection
- Reset recent identities pagination to page 1

## Selecting Resources

### Selecting a Persona (via modal)

- Navigate to the persona's splash page
- Clear any error states
- Load the persona's recent identities

### Selecting an Identity (via search or splash page)

- Navigate to the identity view
- Clear any "identity not found" error state
- Load the identity's history and relations

## State Persistence

### After Creating/Updating an Identity

The recent identities list should refresh to reflect the change, so new/modified identities appear at the top of the splash page.

### Logged-in User

The logged-in user should persist across page refreshes (stored in localStorage).
