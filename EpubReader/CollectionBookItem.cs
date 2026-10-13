using System.Windows;
using EpubReader.Models;
using EpubReader.Services;

namespace EpubReader
{

public class CollectionBookItem
{
	public Book Book { get; set; }

	public string VolumeText { get; set; }

	public string ProgressText
	{
		get
		{
			if (string.IsNullOrEmpty(Book.LastOpenedDate))
			{
				return Loc.FN("{0} فصل · شروع نشده", Book.TotalChapters, Book.TotalChapters);
			}
			return Loc.F("فصل {0} از {1}", Book.LastChapterDisplay, Book.TotalChapters);
		}
	}

	public bool IsCompleted => Book.IsCompleted;

	public string PercentText => Book.PercentDisplay + Loc.Percent;

	public Visibility CompletedVisibility => (!Book.IsCompleted) ? Visibility.Collapsed : Visibility.Visible;
}
}
