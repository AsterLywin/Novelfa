using System.Collections.Generic;

namespace EpubReader.Models
{

public class EpubBook
{
	public string Title { get; set; }

	public string ExtractPath { get; set; }

	public string CoverImagePath { get; set; }

	public List<ChapterItem> Chapters { get; set; } = new List<ChapterItem>();
}
}
