/** The focus key of the page for stack entry `id` (every page is a focus group under this key). */
export function pageFocusKey(id: number): string {
  return 'page-' + String(id);
}
