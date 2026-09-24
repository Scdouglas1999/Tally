/**
 * Scroll positions that keep a focused thing in view. Pure (unit-tested). Scroll containers move with
 * scrollLeft/scrollTop (never a transform: the focus system measures through scroll offsets).
 */

/**
 * The scroll offset that shows [itemStart, itemStart + itemSize) inside a viewport of `viewSize` currently at
 * `current`, keeping `before` pixels free ahead of the item and `after` behind it, moving as little as possible.
 * Clamped to [0, max].
 */
export function reveal(
  current: number,
  viewSize: number,
  itemStart: number,
  itemSize: number,
  before: number,
  after: number,
  max: number,
): number {
  let next = current;
  if (itemStart - before < next) next = itemStart - before;
  else if (itemStart + itemSize + after > next + viewSize) next = itemStart + itemSize + after - viewSize;
  return Math.max(0, Math.min(max, next));
}

/**
 * Offset of `el` relative to `ancestor` (offsetParent chain; unaffected by the stage's scale). `ancestor` must be
 * positioned (relative/absolute) so the chain reaches it.
 */
export function offsetWithin(el: HTMLElement, ancestor: HTMLElement): { left: number; top: number } {
  let left = 0;
  let top = 0;
  let node: HTMLElement | null = el;
  while (node !== null && node !== ancestor) {
    left += node.offsetLeft;
    top += node.offsetTop;
    const parent: Element | null = node.offsetParent;
    if (parent === null) break;
    if (parent !== ancestor && ancestor.contains(parent)) {
      left -= (parent as HTMLElement).scrollLeft;
      top -= (parent as HTMLElement).scrollTop;
    }
    node = parent as HTMLElement;
  }
  return { left, top };
}
