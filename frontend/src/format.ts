import type { Source, Thresholds } from './api'

/** `python_depth` → `Python depth` */
export function humanize(id: string): string {
  const words = id.replace(/_/g, ' ')
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function fixed(value: number, digits = 2): string {
  return value.toFixed(digits)
}

/** Which threshold band a must-have probability falls in. */
export function mustHaveBand(p: number, t: Thresholds): 'met' | 'unclear' | 'not-shown' {
  if (p >= t.mustHavePass) return 'met'
  if (p < t.mustHaveFail) return 'not-shown'
  return 'unclear'
}

export function describeSource(source: Source): string {
  if (source.kind === 'folder') return `folder ${source.path}`
  const n = source.files.length
  return `${n} uploaded file${n === 1 ? '' : 's'}`
}

/** A stable key for a source, for query caching. */
export function sourceKey(source: Source): string {
  return source.kind === 'folder'
    ? `folder:${source.path}`
    : `upload:${source.files.map((f) => `${f.name}:${f.size}:${f.lastModified}`).join('|')}`
}

/** Rough input-token estimate (about 4 characters per token for English text). */
export function estimateTokens(characters: number): number {
  return Math.round(characters / 4)
}
