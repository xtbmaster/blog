var loader_status = "IDLE",
    loader = document.querySelector(".loader");


function load_posts() {
  loader_status = "LOADING";
  loader.innerHTML = "Загружаю еще контента...";
  loader.classList.remove("loader-error");
  loader.removeEventListener("click", load_posts);
  var posts = document.querySelectorAll(".post"),
      last_post = posts[posts.length - 1],
      last_post_id = last_post.getAttribute("data-id");

  fetch('/after/' + last_post_id).then(function(resp) {
    if(resp.ok) {
      resp.text().then(function (fragment) {
        if (fragment.length === 0) {
          loader_status = "DONE";
          loader.remove();
        } else {
          var div = document.createElement("div");
          div.innerHTML = fragment;
          loader.parentNode.insertBefore(div, loader);
          loader_status = "IDLE";
          loader.innerHTML = "...";
        }
      });
    } else {
      loader_status = "ERROR";
      loader.innerHTML = "Ошибка. Нажмите, чтобы повторить";
      loader.classList.add("loader-error");
      loader.addEventListener("click", load_posts);
    }
  });
}


function on_scroll(e) {
  var cont            = document.body,
      full_height     = cont.scrollHeight,
      viewport_height = window.innerHeight,
      scrolled        = window.pageYOffset || document.documentElement.scrollTop || document.body.scrollTop,
      scrolled_bottom = scrolled + viewport_height,
      at_bottom       = scrolled_bottom >= full_height - 200;

  if ( at_bottom && loader_status === "IDLE" )
    load_posts();
}


window.addEventListener("load", function() {
  on_scroll();
  window.addEventListener("scroll", on_scroll);
});
