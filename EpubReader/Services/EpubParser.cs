using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Xml.Linq;
using EpubReader.Models;

namespace EpubReader.Services
{

public static class EpubParser
{
	private class ManifestEntry
	{
		public string Href;
		public string MediaType;
	}

	// آدرس فایل داخل کتاب (با %20 و حروف فارسی کدشده) رو به مسیر واقعی تبدیل می‌کنه
	private static string ResolveHref(string folder, string href)
	{
		string clean = href;
		int hash = clean.IndexOf('#');
		if (hash >= 0)
		{
			clean = clean.Substring(0, hash);
		}
		string raw = Path.Combine(folder, clean.Replace('/', Path.DirectorySeparatorChar));
		try
		{
			string decoded = Path.Combine(folder, Uri.UnescapeDataString(clean).Replace('/', Path.DirectorySeparatorChar));
			if (File.Exists(decoded))
			{
				return decoded;
			}
		}
		catch
		{
		}
		return raw;
	}

	public static EpubBook Load(string epubFilePath)
	{
		EpubBook book = new EpubBook();
		string tempFolder = Path.Combine(Path.GetTempPath(), "EpubReader_" + Guid.NewGuid().ToString("N"));
		Directory.CreateDirectory(tempFolder);
		book.ExtractPath = tempFolder;
		try
		{
		ZipFile.ExtractToDirectory(epubFilePath, tempFolder);
		string containerPath = Path.Combine(tempFolder, "META-INF", "container.xml");
		XDocument containerDoc = XDocument.Load(containerPath);
		XNamespace nsContainer = "urn:oasis:names:tc:opendocument:xmlns:container";
		string opfRelativePath = containerDoc.Descendants(nsContainer + "rootfile").First().Attribute("full-path")
			.Value;
		string opfFullPath = Path.Combine(tempFolder, opfRelativePath.Replace('/', Path.DirectorySeparatorChar));
		string opfFolder = Path.GetDirectoryName(opfFullPath);
		XDocument opfDoc = XDocument.Load(opfFullPath);
		XNamespace nsOpf = "http://www.idpf.org/2007/opf";
		XNamespace nsDc = "http://purl.org/dc/elements/1.1/";
		XElement titleEl = opfDoc.Descendants(nsDc + "title").FirstOrDefault();
		book.Title = ((titleEl != null && !string.IsNullOrWhiteSpace(titleEl.Value)) ? titleEl.Value.Trim() : Path.GetFileNameWithoutExtension(epubFilePath));
		Dictionary<string, ManifestEntry> manifestItems = new Dictionary<string, ManifestEntry>();
		foreach (XElement item in opfDoc.Descendants(nsOpf + "item"))
		{
			XAttribute idAttr = item.Attribute("id");
			XAttribute hrefAttr = item.Attribute("href");
			if (idAttr == null || hrefAttr == null || manifestItems.ContainsKey(idAttr.Value))
			{
				continue;
			}
			XAttribute typeAttr = item.Attribute("media-type");
			manifestItems[idAttr.Value] = new ManifestEntry
			{
				Href = hrefAttr.Value,
				MediaType = ((typeAttr != null) ? typeAttr.Value : null)
			};
		}
		string coverHref = null;
		XElement coverMeta = opfDoc.Descendants(nsOpf + "meta").FirstOrDefault((XElement m) => m.Attribute("name") != null && m.Attribute("name").Value == "cover");
		if (coverMeta != null)
		{
			string coverId = ((coverMeta.Attribute("content") != null) ? coverMeta.Attribute("content").Value : null);
			if (coverId != null && manifestItems.ContainsKey(coverId))
			{
				coverHref = manifestItems[coverId].Href;
			}
		}
		if (coverHref == null)
		{
			var coverItem = manifestItems.Values.FirstOrDefault(v => v.MediaType != null && v.MediaType.StartsWith("image") && v.Href.ToLower().Contains("cover"));
			if (coverItem != null)
			{
				coverHref = coverItem.Href;
			}
		}
		if (coverHref == null)
		{
			var firstImage = manifestItems.Values.FirstOrDefault(v => v.MediaType != null && v.MediaType.StartsWith("image"));
			if (firstImage != null)
			{
				coverHref = firstImage.Href;
			}
		}
		if (coverHref != null)
		{
			string sourcePath = ResolveHref(opfFolder, coverHref);
			if (File.Exists(sourcePath))
			{
				book.CoverImagePath = CopyToPermanentCoverStorage(sourcePath, epubFilePath);
			}
		}
		List<string> spineItems = (from item in opfDoc.Descendants(nsOpf + "itemref")
			where item.Attribute("idref") != null
		select item.Attribute("idref").Value).ToList();
		int index = 1;
		foreach (string idref in spineItems)
		{
			if (manifestItems.ContainsKey(idref))
			{
				string href = manifestItems[idref].Href;
				string fullPath = ResolveHref(opfFolder, href);
				book.Chapters.Add(new ChapterItem
				{
					Title = Loc.F("فصل {0}", index),
					FilePath = fullPath
				});
				index++;
			}
		}
		return book;
		}
		catch
		{
			try
			{
				Directory.Delete(tempFolder, true);
			}
			catch
			{
			}
			throw;
		}
	}

	private static string CopyToPermanentCoverStorage(string sourceImagePath, string epubFilePath)
	{
		string coversFolder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EpubReaderApp", "Covers");
		if (!Directory.Exists(coversFolder))
		{
			Directory.CreateDirectory(coversFolder);
		}
		string extension = Path.GetExtension(sourceImagePath);
		if (string.IsNullOrEmpty(extension))
		{
			extension = ".jpg";
		}
		string key;
		using (MD5 md5 = MD5.Create())
		{
			byte[] hash = md5.ComputeHash(Encoding.UTF8.GetBytes(epubFilePath.ToLowerInvariant()));
			key = BitConverter.ToString(hash).Replace("-", "").ToLowerInvariant();
		}
		string destPath = Path.Combine(coversFolder, "cover_" + key + extension);
		if (!File.Exists(destPath))
		{
			File.Copy(sourceImagePath, destPath, overwrite: true);
		}
		return destPath;
	}
}
}
