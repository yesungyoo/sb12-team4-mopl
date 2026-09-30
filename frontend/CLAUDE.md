# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

React + TypeScript frontend built with Vite. This project uses modern React 19 with strict TypeScript configuration, Tailwind CSS v4, and shadcn/ui components.

## Development Commands

### Running the Application
- `pnpm dev` - Start development server with HMR
- `pnpm preview` - Preview production build locally

### Building
- `pnpm build` - Type-check with `tsc -b` then build with Vite
  - Build outputs to `dist/` directory
  - TypeScript compilation must pass before Vite builds

### Linting
- `pnpm lint` - Run ESLint on all TypeScript/TSX files
  - Uses flat config format (eslint.config.js)
  - Includes React Hooks and React Refresh rules

## Technology Stack

### Core Dependencies
- **React 19.1.1** - UI library with latest features
- **React Router DOM 7.9.4** - Client-side routing with **HashRouter**
- **Zustand 5.0.8** - Lightweight state management
- **Axios 1.13.0** - HTTP client
- **React Hook Form 7.65.0** - Form handling

### UI & Styling
- **Tailwind CSS v4** with `@tailwindcss/vite` plugin
- **shadcn/ui** components (configured in `components.json`)
- **Lucide React** - Icon library

### Design System - Color Palette

**IMPORTANT: Always use the project's color tokens. Never use arbitrary color values.**

The project uses a comprehensive color system defined in `src/index.css` based on Figma design tokens.

#### Available Color Scales

**Gray Scale** (Primary neutral colors):
- `gray-50` through `gray-950` (11 steps)
- Use for: text, backgrounds, borders, neutral UI elements

**Pink (Brand)** (Primary brand colors):
- `pink-50` through `pink-950` (11 steps)
- Use for: primary actions, brand elements, highlights

**Semantic Colors**:
- `red-notification` - Error and notification states
- `background` - Main app background (#010102)
- `white` - Pure white (#FFFFFF)

#### Color Usage Rules - MUST FOLLOW

1. **Always use Tailwind color classes** from the defined palette
   ```tsx
   // ✅ CORRECT: Use defined color tokens
   <div className="bg-gray-50 text-gray-950">
   <button className="bg-pink-500 text-white">

   // ❌ WRONG: Never use arbitrary colors
   <div className="bg-[#f5f5f5] text-[#333]">
   <div style={{ backgroundColor: '#ff4a64' }}>
   ```

2. **Use semantic naming for consistency**
   - Primary text: `text-gray-950`
   - Secondary/muted text: `text-gray-500` or `text-gray-600`
   - Light backgrounds: `bg-gray-50`, `bg-gray-100`
   - Borders: `border-gray-200`, `border-gray-300`
   - Primary buttons: `bg-pink-500`, `hover:bg-pink-600`
   - Error states: `text-red-notification`, `bg-red-notification`

3. **For shadcn/ui components**, use CSS variables
   ```tsx
   // ✅ CORRECT: shadcn/ui semantic variables
   <Button variant="default">Primary</Button>  // Uses --primary
   <Button variant="destructive">Delete</Button>  // Uses --destructive

   // Or use the CSS variables directly in custom components
   className="bg-primary text-primary-foreground"
   ```

4. **Dark mode support**
   - The `.dark` class automatically switches shadcn/ui component colors
   - Custom components should use the gray scale which works well in both modes

5. **Brand color usage**
   - Use `pink-*` scale sparingly for primary CTAs and brand moments
   - Default to `gray-*` for most UI elements
   - Primary action button: `bg-pink-500 hover:bg-pink-600 text-white`

#### shadcn/ui Semantic Variables

Available CSS variables for consistent theming:
- `--background`, `--foreground` - Page background and text
- `--primary`, `--primary-foreground` - Primary brand color (pink-500)
- `--secondary`, `--secondary-foreground` - Secondary actions (gray-based)
- `--muted`, `--muted-foreground` - Muted/disabled states
- `--accent`, `--accent-foreground` - Accent highlights
- `--destructive`, `--destructive-foreground` - Error/delete actions
- `--border`, `--input`, `--ring` - Form and border colors

These are automatically used by shadcn/ui components via the `variant` prop.

### Typography System

**IMPORTANT: Always use the project's typography classes. Never use custom inline font styles.**

The project uses a comprehensive typography system with 24 predefined classes based on Figma design tokens, using the Pretendard Variable font optimized for Korean and Latin characters.

#### Typography Rules - MUST FOLLOW

1. **Always use predefined typography classes**
   ```tsx
   // ✅ CORRECT: Use typography utility classes
   <h1 className="text-header1-b">Page Title</h1>
   <p className="text-body2-m text-neutral-900">Body text</p>
   <span className="text-caption1-m text-neutral-500">Helper text</span>

   // ❌ WRONG: Never use custom font styles
   <h1 style={{ fontSize: '24px', fontWeight: 700 }}>Title</h1>
   <p className="text-[24px] font-bold">Text</p>
   ```

2. **Maintain visual hierarchy**
   - Use Header classes (`.text-header1-b`, `.text-header2-b`) for page/section titles
   - Use Title classes (`.text-title1-b`, `.text-title1-sb`) for card titles and sub-sections
   - Use Body classes (`.text-body1-m` ~ `.text-body4-m`) for content text
   - Use Caption classes (`.text-caption1-m`, `.text-caption2-m`) for meta information

3. **Choose appropriate line heights**
   - Use tight spacing (no suffix) for headers, buttons, and labels
   - Use spaced variants (`-140`, `-150`, `-160`) for multi-line body text
   ```tsx
   // Single line / tight layout
   <button className="text-body2-sb">Click me</button>

   // Multi-line body text
   <p className="text-body2-m-160">
     Long paragraph text with better readability
     using 160% line height...
   </p>
   ```

4. **Separate typography from colors**
   - Typography classes only define font properties (family, size, weight, line-height, letter-spacing)
   - Always add color classes separately using Tailwind utilities
   ```tsx
   // ✅ CORRECT: Separate concerns
   <h1 className="text-header1-b text-gray-950">Title</h1>
   <p className="text-body2-m text-gray-700">Text</p>

   // ❌ WRONG: Typography classes don't include colors
   // Don't assume typography classes set colors
   ```

5. **Use consistent mapping for similar contexts**
   - Page titles: `text-header1-b`
   - Section headers: `text-header2-b`
   - Card titles: `text-title1-b`
   - Button labels: `text-body2-sb` or `text-body3-sb`
   - Primary body text: `text-body2-m` or `text-body2-m-160`
   - Labels and tags: `text-body3-sb`
   - Timestamps and meta info: `text-caption1-m`

#### Available Typography Classes (24 Total)

- **Header** (4): `text-header1-b`, `text-header1-b-160`, `text-header1-sb`, `text-header2-b`
- **Title** (3): `text-title1-b`, `text-title1-sb`, `text-title1-b-140`
- **Body** (13): `text-body1-b`, `text-body1-sb`, `text-body1-m`, `text-body2-b`, `text-body2-sb`, `text-body2-m`, `text-body2-m-140`, `text-body2-m-160`, `text-body3-b`, `text-body3-sb`, `text-body3-m`, `text-body3-m-150`, `text-body4-m`
- **Caption** (4): `text-caption1-sb`, `text-caption1-m`, `text-caption2-sb`, `text-caption2-m`

**For complete specifications**, see `TYPOGRAPHY.md` which includes:
- Full table of all 24 classes with sizes, weights, and use cases
- Font information (Pretendard Variable)
- Design tokens (weights, sizes, line heights, letter spacing)
- Best practices and examples

#### Typography Best Practices

1. **Consistency is key**: Same content type → same typography class across the app
2. **Don't mix arbitrary font utilities**: Avoid `text-[16px]`, `font-[600]`, `leading-[1.5]` when predefined classes exist
3. **Use semantic meaning**: Choose classes based on content hierarchy, not just visual preference
4. **Test with Korean text**: Pretendard is optimized for Korean; test both English and Korean samples
5. **Respect letter spacing**: All classes have -2% letter spacing for optimal Korean readability

### Real-time Communication
- **@stomp/stompjs 7.2.1** - STOMP protocol over WebSocket
- **sockjs-client 1.6.1** - WebSocket fallback support

### Input Handling

#### Korean IME Composition Support

**IMPORTANT: Always handle IME composition events for Enter key actions**

When implementing input fields that trigger actions on Enter key press, you must check the composition state to support Korean and other IME-based languages properly.

**Pattern to Follow:**
```tsx
// ✅ CORRECT: Check isComposing before handling Enter key
const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
    e.preventDefault();
    handleSubmit(e);
  }
};

// ❌ WRONG: Don't ignore composition state
const handleKeyDown = (e: KeyboardEvent<HTMLInputElement>) => {
  if (e.key === 'Enter' && !e.shiftKey) {  // Missing isComposing check
    e.preventDefault();
    handleSubmit(e);
  }
};
```

**Why This Matters:**
- Korean input uses IME (Input Method Editor) which requires composition
- Without checking `isComposing`, pressing Enter during composition will submit the form instead of completing the character
- This applies to all CJK languages (Chinese, Japanese, Korean) and other IME-based inputs

**When to Apply:**
- Text input fields with Enter key submission (chat inputs, search bars, forms)
- Any keyboard event handler that responds to Enter key
- Both `<input>` and `<textarea>` elements

### Build & Dev Tools
- **Vite 7.1.7** - Build tool and dev server
- **TypeScript 5.9.3** - Type system with strict mode enabled
- **pnpm** - Package manager

## Architecture

### File Organization
```
src/
├── lib/
│   ├── api/           # API client and domain modules
│   ├── types/         # models
│   ├── stores/        # Zustand state stores
│   ├── hooks/         # Custom React hooks
│   └── utils/         # Utility functions
├── assets/            # SVG icons and illustrations
├── components/        # Reusable React components (follows shadcn/ui patterns)
│   ├── ui/            # Reusable UI components (input, button...)
│   └── layout/        # Reusable Layout UI components
└── pages/             # Page components
    └── {path}/        # Reusable UI components (input, button...)
        ├── page.tsx   # Page component (one page.tsx per {path})
        └── components/ # Components used in pages
```

### Path Aliases
- `@/` maps to `src/`
- `@/components`, `@/lib`, `@/hooks`, `@/ui` are configured via tsconfig paths

### Routing
**IMPORTANT: This project uses React Router's HashRouter**
- Hash-based routing optimized for CSR environments
- Supports refresh without server configuration
- Query parameters are synced with URL to preserve state on refresh

## Critical Patterns - MUST FOLLOW

### Data Fetching Rules (READ Operations)

**RULE: UI components MUST only use stores from `@/lib/stores/` for data fetching**

1. **Never** call `@/lib/api/` directly from components for data fetching
2. **Always** use store methods like `fetch()`, `fetchMore()` etc.
3. Store handles loading states, error handling, and data caching

**Example:**
```tsx
// ✅ CORRECT: Use store for data fetching
const { data, loading, fetch } = useFeedStore();

useEffect(() => {
  fetch();
}, []);

// ❌ WRONG: Direct API call from component
import { getFeedList } from '@/lib/api/feeds';
// Don't call API directly in components for fetching
```

### CUD Action Rules (CREATE/UPDATE/DELETE Operations)

**RULE: For CUD operations, call API directly then sync store**

1. **Call API directly** from `@/lib/api/` modules
2. **Sync store state** after successful API call using store's `add`, `update`, `delete` methods
3. **Handle errors** without changing store state if API fails

**Example:**
```tsx
// ✅ CORRECT: CUD pattern
const handleCreateFeed = async (data: FeedCreateRequest) => {
  try {
    // 1. Call API directly
    const newFeed = await createFeed(data);

    // 2. Sync store state
    useFeedStore.getState().add(newFeed);

    // 3. Success handling
    toast.success('Feed created');
  } catch (error) {
    // 4. Error handling (don't modify store)
    toast.error('Failed to create feed');
  }
};
```

## State Management

### Store Architecture
All stores follow standardized interfaces:
- `BaseStore<T>` - Base store interface
- `ListStore<T>` - For list data
- `PaginatedStore<T>` - For paginated data

### Creating Stores - Implementation Patterns

#### When to Use Each Store Type

**PaginatedStore<T, P extends CursorParams>** - Use for cursor-paginated API endpoints
- APIs that return `CursorResponse` (with `nextCursor`, `hasNext`, `totalCount`)
- Examples: User lists, content lists, playlist lists
- Supports infinite scroll and "load more" patterns

**ListStore<T, P>** - Use for simple list data without pagination
- APIs that return arrays directly
- Small datasets that don't need pagination
- Examples: Tags, categories, settings lists

**BaseStore<T, P>** - Use for single resource/object
- APIs that return a single object
- Examples: User profile, single content item, configuration

#### PaginatedStore Implementation Pattern

**MUST FOLLOW**: All paginated stores use the `createPaginatedStoreActions` factory from `@/lib/stores/actions.ts`

```typescript
// Example: src/lib/stores/useUserStore.ts
import { create } from 'zustand';
import { getUsers } from '@/lib/api/users';
import { createPaginatedStoreActions } from '@/lib/stores/actions';
import type { UserDto, FindUsersParams } from '@/lib/types';
import type { PaginatedStore } from '@/lib/stores/types';

const useUserStore = create<PaginatedStore<UserDto, FindUsersParams>>((set, get) =>
  createPaginatedStoreActions<UserDto, FindUsersParams>({
    set,
    get,
    fetchApi: getUsers,              // API function that returns CursorResponse
    initialData: {
      params: { limit: 20 },          // Default query parameters
    },
    // keyExtractor: (user) => user.id  // Optional: only if key is NOT 'id'
  })
);

export default useUserStore;
```

**Key Points**:
1. **Type Imports**: Import types from `@/lib/types` and `PaginatedStore` from `@/lib/stores/types`
2. **fetchApi**: Must be a function that accepts params and returns a `Promise<CursorResponse>`
3. **initialData.params**: Set default pagination (`limit`) and other query params
4. **keyExtractor**: Default extracts `id` property. Only override if using different key name

#### Available Store Methods

**Data Fetching**:
- `fetch(options?)` - Fetch first page (resets data)
- `fetchMore(options?)` - Fetch next page (appends to data)
- `hasNext()` - Returns boolean indicating if more data available
- `count()` - Returns total count from cursor response

**Data Mutations** (for optimistic updates after CUD operations):
- `add(newItem)` - Add item to list (respects sorting, prevents duplicates)
- `update(id, partialData)` - Update existing item
- `delete(id)` - Remove item from list

**State Management**:
- `data` - Array of items (type `T[]`)
- `loading` - Boolean loading state
- `error` - Error message string or undefined
- `params` - Current query parameters
- `updateParams(newParams, options?)` - Update params and auto-fetch
- `cursorState` - Pagination state (`nextCursor`, `nextIdAfter`, `hasNext`, `totalCount`)

**Cleanup**:
- `clear()` - Clear both data and error
- `clearData()` - Reset data to initial state
- `clearError()` - Clear error message

#### Usage Example in Components

```tsx
import useUserStore from '@/lib/stores/useUserStore';
import { createUser } from '@/lib/api/users';

function UserListPage() {
  const { data, loading, error, fetch, fetchMore, hasNext, updateParams } = useUserStore();

  // Initial fetch
  useEffect(() => {
    fetch();
  }, []);

  // Search/filter
  const handleSearch = (email: string) => {
    updateParams({ emailLike: email }); // Auto-fetches with new params
  };

  // Load more (infinite scroll)
  const handleLoadMore = () => {
    if (hasNext() && !loading) {
      fetchMore();
    }
  };

  // CUD operation example
  const handleCreateUser = async (userData: UserCreateRequest) => {
    try {
      // 1. Call API directly
      const newUser = await createUser(userData);

      // 2. Sync store state
      useUserStore.getState().add(newUser);

      toast.success('User created');
    } catch (error) {
      toast.error('Failed to create user');
    }
  };

  return (
    <div>
      {data.map(user => <UserCard key={user.id} user={user} />)}
      {loading && <Spinner />}
      {error && <ErrorMessage message={error} />}
    </div>
  );
}
```

### Store Pattern - MUST FOLLOW
All stores use the `execute()` utility from `src/lib/stores/utils.ts` for consistent async action handling:

```typescript
// Example from useAuthStore.ts
await execute(
  set, get,
  () => signIn({ username, password }),
  { shouldThrow: true }
)
```

### Authentication
- **Central auth state** managed in `useAuthStore`
- **Automatic token refresh** handled by store
- All authenticated requests use tokens from auth store

## API Layer

### API Client Pattern - MUST FOLLOW
- **Single centralized HTTP client** at `src/lib/api/client.ts` (Axios-based)
- **Automatic JWT token injection and refresh**
- **All API modules use this client** for consistency
- Domain-organized API modules: `auth.ts`, `users.ts`, `contents.ts`, `playlists.ts`, etc.
- OpenAPI spec available in `api.json` for backend contract

### Type Import Rules - MUST FOLLOW

**RULE: ONLY import types from `@/lib/types`. NEVER import from `@/lib/types/api`**

`@/lib/types` is the **single source of truth** for all type imports across the entire codebase.

#### ✅ CORRECT Pattern
```typescript
// API modules - use pre-exported query parameter types
import type {
  UserDto,
  UserCreateRequest,
  CursorResponseUserDto,
  FindUsersParams,  // ✅ Pre-defined query params
} from '@/lib/types';
```

```typescript
// Store modules
import type {
  ContentDto,
  CursorResponseContentDto,
  FindContentsParams,
} from '@/lib/types';
```

```typescript
// Components
import type { PlaylistDto, UserSummary } from '@/lib/types';
```

#### ❌ WRONG Pattern
```typescript
// NEVER import directly from api.ts
import type { components } from '@/lib/types/api'; // ❌ WRONG
import type { operations } from '@/lib/types/api'; // ❌ WRONG
import type { paths } from '@/lib/types/api'; // ❌ WRONG

// NEVER use operations/components from @/lib/types
import type { operations } from '@/lib/types'; // ❌ WRONG
type Params = operations['findUsers']['parameters']['query']; // ❌ WRONG
```

#### Why This Rule Exists
1. **Single Source of Truth**: All type imports go through one entry point (`@/lib/types`)
2. **Encapsulation**: `@/lib/types/api` is an internal implementation detail
3. **Store Compatibility**: Stores, API modules, and components all use the same import path
4. **Pre-defined Types**: All commonly used types (including query params) are pre-exported
5. **Maintainability**: Type changes only need to be managed in `@/lib/types/index.ts`

#### Available Type Exports from `@/lib/types`
- **Schema types**: `UserDto`, `ContentDto`, `PlaylistDto`, `ReviewDto`, etc.
- **Request types**: `UserCreateRequest`, `ContentUpdateRequest`, `PlaylistCreateRequest`, etc.
- **Response types**: `CursorResponseUserDto`, `CursorResponseContentDto`, etc.
- **Query parameter types**: `FindUsersParams`, `FindContentsParams`, `FindPlaylistsParams`, etc.
- **Common types**: `CursorParams`, `SortDirection`, `ErrorResponse`, `UserRole`, `ContentType`, etc.

**Note**: If you need a type that's not exported, add it to `@/lib/types/index.ts` rather than importing from `@/lib/types/api`.

## TypeScript Configuration

### Strict Type Checking
The project uses strict TypeScript settings:
- `strict: true`
- `noUnusedLocals: true`
- `noUnusedParameters: true`
- `noFallthroughCasesInSwitch: true`
- `noUncheckedSideEffectImports: true`
- `erasableSyntaxOnly: true`

### Module Resolution
- Uses `bundler` module resolution
- Allows importing `.ts/.tsx` extensions
- Forces module detection

## ESLint Configuration

Uses ESLint flat config with:
- TypeScript ESLint recommended rules
- React Hooks recommended-latest rules
- React Refresh Vite rules
- Target: ES2020
- Ignores: `dist/` directory

## Figma MCP Integration Rules
These rules define how to translate Figma inputs into code for this project and must be followed for every Figma-driven change.

### Required flow (do not skip)
1. Run get_design_context first to fetch the structured representation for the exact node(s).
2. If the response is too large or truncated, run get_metadata to get the high‑level node map and then re‑fetch only the required node(s) with get_design_context.
3. Run get_screenshot for a visual reference of the node variant being implemented.
4. Only after you have both get_design_context and get_screenshot, handle assets before implementation:
   - First check if required assets already exist in `src/assets/`
   - If missing, ask user to download from Figma and save to `src/assets/` using the **Figma layer name as-is** for the filename (e.g., if Figma layer is "ic_google", save as `ic_google.svg`)
   - NEVER use nodeId or arbitrary names for asset files
   - NEVER use localhost URLs from Figma MCP in production code
   - Only then proceed with implementation
5. Translate the output (usually React + Tailwind) into this project's conventions, styles and framework.  Reuse the project's color tokens, components, and typography wherever possible.
6. Validate against Figma for 1:1 look and behavior before marking complete.

### Implementation rules
- **Component mapping priority**: When translating Figma designs to code, use shadcn/ui components as the first choice:
  1. Use shadcn/ui components when Figma elements match standard patterns (Button, Input, Dialog, Card, Select, Checkbox, etc.)
  2. Extend shadcn/ui components with variants or composition if additional customization is needed
  3. Only create custom components when no shadcn/ui equivalent exists or when design significantly differs
- Treat the Figma MCP output (React + Tailwind) as a representation of design and behavior, not as final code style.
- Replace Tailwind utility classes with the project's preferred utilities/design‑system tokens when applicable.
- Reuse existing components (e.g., buttons, inputs, typography, icon wrappers) instead of duplicating functionality.
- Use the project's color system, typography scale, and spacing tokens consistently.
- **Typography mapping**: Replace Figma MCP font outputs with project typography classes:
  - Analyze font size, weight, and line-height from Figma output
  - Map to the closest matching typography class (see Typography System section)
  - Never use arbitrary `text-[size]`, `font-[weight]`, or `leading-[value]` classes
  - Example: Figma's "20px Bold" → use `text-title1-b` class
  - Example: Figma's "16px Medium 1.6" → use `text-body2-m-160` class
- Respect existing routing, state management, and data‑fetch patterns already adopted in the repo.
- **State management and props pattern**: To prevent props drilling and maintain clean component architecture:
  - Define only essential props needed for display/interaction in UI components (e.g., styling, callbacks, presentational data)
  - Fetch data directly from stores within components using hooks (e.g., `useUserStore()`, `useContentStore()`)
  - DO NOT pass fetched data through multiple component levels as props
  - Components that need data should subscribe to stores directly rather than receiving data via props chain
- Strive for 1:1 visual parity with the Figma design. When conflicts arise, prefer design‑system tokens and adjust spacing or sizes minimally to match visuals.
- Validate the final UI against the Figma screenshot for both look and behavior.
- **Asset handling**:
  - ALWAYS reference assets from `src/assets/` using import statements (e.g., `import icGoogle from '@/assets/ic_google.svg'`)
  - NEVER use localhost URLs (e.g., `http://localhost:...`) or external Figma URLs directly in code
  - Before implementation, verify that all required assets exist in `src/assets/`
  - If an asset doesn't exist locally, ask the user to download it from Figma and save to `src/assets/` before proceeding
  - Use Figma layer name as-is for asset filenames to maintain traceability (e.g., Figma layer "ic_google" → `ic_google.svg`, "ic_kakao" → `ic_kakao.svg`)
