export const checkImageUrl = async (imageUrl: string) => {
  try {
    const response = await fetch(imageUrl);
    if (
      response.ok &&
      response.headers.get("content-type")?.startsWith("image/")
    ) {
      return true;
    } else {
      return false;
    }
  } catch (error) {
    return false;
  }
};
