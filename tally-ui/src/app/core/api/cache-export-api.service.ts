import { HttpClient, HttpErrorResponse, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

export type ExportFormat = 'xlsx' | 'pdf';

@Injectable({
  providedIn: 'root',
})
export class CacheExportApiService {
  constructor(private http: HttpClient) {}

  download(
    category: string,
    dataset: string,
    format: ExportFormat,
    query: Record<string, string> = {}
  ): Observable<HttpResponse<Blob>> {
    let params = new HttpParams().set('format', format);
    Object.entries(query).forEach(([key, value]) => {
      if (value !== undefined && value !== null && `${value}`.trim()) {
        params = params.set(key, `${value}`.trim());
      }
    });

    return this.http.get(`/api/cache/export/${category}/${dataset}`, {
      params,
      observe: 'response',
      responseType: 'blob',
    });
  }

  downloadAndSave(
    category: string,
    dataset: string,
    format: ExportFormat,
    query: Record<string, string> = {}
  ): void {
    this.download(category, dataset, format, query).subscribe({
      next: (response) => {
        const blob = response.body;
        if (!blob) {
          this.showError('Download failed because the file response was empty.');
          return;
        }

        const fileName = this.resolveFileName(response) || `${dataset}.${format}`;
        this.saveBlob(blob, fileName);
      },
      error: (error: HttpErrorResponse) => {
        this.resolveErrorMessage(error).then((message) => {
          this.showError(message);
        });
      },
    });
  }

  private resolveFileName(response: HttpResponse<Blob>): string {
    const contentDisposition = response.headers.get('content-disposition') || '';
    const utf8Match = contentDisposition.match(/filename\*=UTF-8''([^;]+)/i);
    if (utf8Match?.[1]) {
      return decodeURIComponent(utf8Match[1]);
    }
    const basicMatch = contentDisposition.match(/filename="?([^"]+)"?/i);
    return basicMatch?.[1] || '';
  }

  private saveBlob(blob: Blob, fileName: string): void {
    const nav = window.navigator as Navigator & {
      msSaveOrOpenBlob?: (blob: Blob, defaultName?: string) => boolean;
    };
    if (typeof nav.msSaveOrOpenBlob === 'function') {
      nav.msSaveOrOpenBlob(blob, fileName);
      return;
    }

    const objectUrl = window.URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = objectUrl;
    link.download = fileName;
    link.style.display = 'none';
    document.body.appendChild(link);
    link.click();
    window.setTimeout(() => {
      document.body.removeChild(link);
      window.URL.revokeObjectURL(objectUrl);
    }, 1000);
  }

  private async resolveErrorMessage(error: HttpErrorResponse): Promise<string> {
    if (error.error instanceof Blob) {
      try {
        const text = await error.error.text();
        const parsed = JSON.parse(text) as { detail?: string; message?: string };
        return parsed.detail || parsed.message || 'Download failed.';
      } catch (parseError) {
        return 'Download failed.';
      }
    }

    if (typeof error.error?.detail === 'string') {
      return error.error.detail;
    }

    return 'Download failed.';
  }

  private showError(message: string): void {
    window.alert(message);
  }
}
