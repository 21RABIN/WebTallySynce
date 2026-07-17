import { HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

@Injectable()
export class CompanyContextInterceptor implements HttpInterceptor {
  private readonly storageKey = 'tally-ui.selected-company';

  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    if (!request.url.startsWith('/api/') || request.url.startsWith('/api/auth/')) {
      return next.handle(request);
    }
    if (request.url.startsWith('/api/cache/masters/companies')) {
      return next.handle(request);
    }

    const company = (localStorage.getItem(this.storageKey) || '').trim();
    if (!company || request.params.has('company') || request.headers.has('X-Company')) {
      return next.handle(request);
    }

    return next.handle(request.clone({
      params: request.params.set('company', company),
      setHeaders: {
        'X-Company': company,
      },
    }));
  }
}
