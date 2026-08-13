const MAX_UPLOAD_BYTES = 8 * 1024 * 1024;
const MAX_WIDTH = 1400;
const MAX_HEIGHT = 1400;
const JPEG_QUALITY = 0.72;
const MAX_DATA_URL_LENGTH = 900_000;

const ACCEPTED_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

export function validateWallpaperFile(file: File): string | null {
  if (!ACCEPTED_TYPES.includes(file.type)) {
    return 'Please choose a JPG, PNG, or WebP image.';
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    return 'Image must be 8 MB or smaller.';
  }
  return null;
}

function loadImageFromFile(file: File): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file);
    const image = new Image();

    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('Unable to read the selected image.'));
    };
    image.src = url;
  });
}

function canvasToDataUrl(canvas: HTMLCanvasElement, quality: number): string {
  return canvas.toDataURL('image/jpeg', quality);
}

export async function compressWallpaperImage(file: File): Promise<string> {
  const validationError = validateWallpaperFile(file);
  if (validationError) {
    throw new Error(validationError);
  }

  const image = await loadImageFromFile(file);
  const scale = Math.min(1, MAX_WIDTH / image.width, MAX_HEIGHT / image.height);
  const width = Math.max(1, Math.round(image.width * scale));
  const height = Math.max(1, Math.round(image.height * scale));

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;

  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('Unable to process the image in this browser.');
  }

  context.drawImage(image, 0, 0, width, height);

  let quality = JPEG_QUALITY;
  let dataUrl = canvasToDataUrl(canvas, quality);

  while (dataUrl.length > MAX_DATA_URL_LENGTH && quality > 0.35) {
    quality -= 0.08;
    dataUrl = canvasToDataUrl(canvas, quality);
  }

  if (dataUrl.length > MAX_DATA_URL_LENGTH) {
    throw new Error('Image is too large after compression. Try a smaller photo.');
  }

  return dataUrl;
}
