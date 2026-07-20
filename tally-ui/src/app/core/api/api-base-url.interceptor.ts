import { HttpEvent, HttpHandler, HttpInterceptor, HttpRequest } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { environment } from '../../../environments/environment';

@Injectable()
export class ApiBaseUrlInterceptor implements HttpInterceptor {
  intercept(request: HttpRequest<unknown>, next: HttpHandler): Observable<HttpEvent<unknown>> {
    const apiBaseUrl = (environment.apiBaseUrl || '').replace(/\/$/, '');

    if (!apiBaseUrl || !request.url.startsWith('/api/')) {
      return next.handle(request);
    }

    return next.handle(request.clone({
      url: `${apiBaseUrl}${request.url}`,
    }));
  }
}
