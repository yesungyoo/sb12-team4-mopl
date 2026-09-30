# Typography System

A complete typography system built based on the Figma design system.

## Installed Packages

- **Tailwind CSS v4.1.16**: `@tailwindcss/vite`, `tailwindcss`
- **Pretendard v1.3.9**: Variable font optimized for Korean and Latin characters

## Configuration Files

### 1. `vite.config.ts`
```typescript
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') }
  }
})
```

### 2. `src/index.css`
- Tailwind CSS imports
- Pretendard Variable font import
- 24 custom typography utility classes defined

### 3. `components.json`
- shadcn/ui configuration file
- New York style with CSS variables enabled

## Typography Classes (24 Total)

### Header (4 styles)
| Class | Font Size | Weight | Line Height | Letter Spacing | Use Case |
|-------|-----------|--------|-------------|----------------|----------|
| `.text-header1-b` | 24px | 700 (Bold) | 1.0 | -0.48px | Page titles |
| `.text-header1-b-160` | 24px | 700 (Bold) | 1.6 | -0.48px | Multi-line titles |
| `.text-header1-sb` | 24px | 600 (SemiBold) | 1.0 | -0.48px | Subtitles |
| `.text-header2-b` | 22px | 800 (ExtraBold) | 1.4 | -0.44px | Section headers |

### Title (3 styles)
| Class | Font Size | Weight | Line Height | Letter Spacing | Use Case |
|-------|-----------|--------|-------------|----------------|----------|
| `.text-title1-b` | 20px | 700 (Bold) | 1.0 | -0.4px | Card titles |
| `.text-title1-sb` | 20px | 600 (SemiBold) | 1.0 | -0.4px | Sub-titles |
| `.text-title1-b-140` | 20px | 700 (Bold) | 1.4 | -0.4px | Multi-line titles |

### Body (13 styles)
| Class | Font Size | Weight | Line Height | Letter Spacing | Use Case |
|-------|-----------|--------|-------------|----------------|----------|
| `.text-body1-b` | 18px | 700 (Bold) | 1.0 | -0.36px | Emphasized text |
| `.text-body1-sb` | 18px | 600 (SemiBold) | 1.0 | -0.36px | Important info |
| `.text-body1-m` | 18px | 500 (Medium) | 1.0 | -0.36px | Regular body (large) |
| `.text-body2-b` | 16px | 700 (Bold) | 1.0 | -0.32px | Emphasized body |
| `.text-body2-sb` | 16px | 600 (SemiBold) | 1.0 | -0.32px | Button labels |
| `.text-body2-m` | 16px | 500 (Medium) | 1.0 | -0.32px | Default body text |
| `.text-body2-m-140` | 16px | 500 (Medium) | 1.4 | -0.32px | Body (wider spacing) |
| `.text-body2-m-160` | 16px | 500 (Medium) | 1.6 | -0.32px | Body (widest spacing) |
| `.text-body3-b` | 14px | 700 (Bold) | 1.0 | -0.28px | Small emphasized text |
| `.text-body3-sb` | 14px | 600 (SemiBold) | 1.0 | -0.28px | Labels |
| `.text-body3-m` | 14px | 500 (Medium) | 1.0 | -0.28px | Small body text |
| `.text-body3-m-150` | 14px | 500 (Medium) | 1.5 | -0.28px | Small body (spaced) |
| `.text-body4-m` | 13px | 500 (Medium) | 1.0 | -0.26px | Extra small body text |

### Caption (4 styles)
| Class | Font Size | Weight | Line Height | Letter Spacing | Use Case |
|-------|-----------|--------|-------------|----------------|----------|
| `.text-caption1-sb` | 12px | 600 (SemiBold) | 1.0 | -0.24px | Helper text |
| `.text-caption1-m` | 12px | 500 (Medium) | 1.0 | -0.24px | Regular captions |
| `.text-caption2-sb` | 11px | 600 (SemiBold) | 1.0 | -0.22px | Small labels |
| `.text-caption2-m` | 11px | 500 (Medium) | 1.0 | -0.22px | Meta information |

## Usage

### Basic Usage
```tsx
<h1 className="text-header1-b">Page Title</h1>
<p className="text-body2-m-160">
  This is body text with 160% line height.
  It provides better readability for multi-line content.
</p>
<span className="text-caption1-m text-neutral-500">
  2025-01-29
</span>
```

### Combining with Tailwind Utilities
```tsx
<div className="text-body2-m text-neutral-900 hover:text-blue-600">
  Clickable text
</div>

<button className="text-body2-sb text-white bg-blue-600">
  Button
</button>
```

## Demo Page

- **File**: `src/pages/typography-demo/page.tsx`
- Visually demonstrates all 24 typography styles
- Includes both English and Korean sample text

To view the demo:
```bash
pnpm dev
```

## Font Information

### Pretendard Variable
- **File**: `pretendard/dist/web/variable/pretendardvariable.css`
- **Features**:
  - Variable Font technology
  - Weight range: 100~900 (project uses 500, 600, 700, 800)
  - Full support for Korean, Latin, numbers, and special characters
  - Optimized for macOS, Windows, and Linux
- **Fallback stack**: Pretendard, -apple-system, BlinkMacSystemFont, system-ui, ...

## Design Tokens

### Font Weights
- **Medium**: 500 (regular body text)
- **SemiBold**: 600 (important information, buttons)
- **Bold**: 700 (emphasis, headings)
- **ExtraBold**: 800 (special emphasis)

### Font Sizes
- 11px, 12px, 13px, 14px, 16px, 18px, 20px, 22px, 24px

### Line Heights
- **1.0**: Tight layout (headers, buttons)
- **1.4**: Moderate spacing (short body text)
- **1.5**: Wide spacing (regular body text)
- **1.6**: Extra wide spacing (long-form content)

### Letter Spacing
All text uses -2% letter spacing (optimized for Korean typography)

## Best Practices

1. **Consistency**: Always use the same typography class for the same purpose
2. **Hierarchy**: Maintain visual hierarchy: Header > Title > Body > Caption
3. **Readability**: Use classes with line-height for long-form content (e.g., `text-body2-m-160`)
4. **Color Separation**: Typography classes handle style only; use Tailwind utilities for colors

## Figma Reference

- **Figma Node ID**: `2174:35024`
- **File Structure**: Typography > Header / Title / Body / Caption
- **Font Family**: Pretendard
- **Letter Spacing**: -2% applied to all styles

## Changelog

- **2025-01-29**: Initial typography system implementation
  - Installed Tailwind CSS v4
  - Applied Pretendard font
  - Created 24 custom utility classes
  - Configured shadcn/ui
