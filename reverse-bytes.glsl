uint revertBytes(uint original) {
	return ((original >> 24) & 255) | (((original >> 16) & 255) << 8) |
			(((original >> 8) & 255) << 16) | ((original & 255) << 24);
}
