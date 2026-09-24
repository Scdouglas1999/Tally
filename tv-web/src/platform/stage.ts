/**
 * The 1920x1080 canvas every screen is designed on, scaled to the window. Tizen and webOS apps usually get a
 * 1920x1080 viewport already (scale 1); a 1280x720 webOS set or a browser window gets the whole canvas scaled.
 */
export const CANVAS_WIDTH = 1920;
export const CANVAS_HEIGHT = 1080;

export function stageScale(width: number, height: number): number {
  return Math.min(width / CANVAS_WIDTH, height / CANVAS_HEIGHT);
}

export function createStage(): HTMLElement {
  const stage = document.createElement('div');
  stage.id = 'tally-stage';
  document.body.appendChild(stage);
  const fit = (): void => {
    const scale = stageScale(window.innerWidth, window.innerHeight);
    const left = Math.round((window.innerWidth - CANVAS_WIDTH * scale) / 2);
    const top = Math.round((window.innerHeight - CANVAS_HEIGHT * scale) / 2);
    stage.style.transform = scale === 1 && left === 0 && top === 0 ? '' : `translate(${left}px, ${top}px) scale(${scale})`;
  };
  fit();
  window.addEventListener('resize', fit);
  return stage;
}
